// Persistent JSON-lines worker using upstream public APIs; no upstream source edits.
#include "ame/engine/engine.h"
#include "ame/core/json.h"
#ifdef AME_HAS_LMEM
#include "ame/mem/mem.h"
#endif
#ifdef AME_HAS_CHEAP
#include "cheap_rerank.hpp"
#endif
#include <chrono>
#include <cstdlib>
#include <algorithm>
#include <ctime>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <memory>
#include <stdexcept>
using ame::Json;
using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point t) { return std::chrono::duration<double,std::milli>(Clock::now()-t).count(); }
static const Json& required(const Json& j, const std::string& k) {
  auto p=j.find(k); if(!p) throw std::runtime_error("missing field: "+k); return *p;
}
int main(int argc,char** argv) {
 try {
  if(argc!=5) throw std::runtime_error("usage: worker corpus.json dict.txt|- graph|lmem cache.bin");
  std::string mode=argv[3];
  if(mode!="graph" && mode!="graph-cheap" && mode!="lmem") throw std::runtime_error("unknown mode");
  auto start=Clock::now();
  std::ifstream in(argv[1]); if(!in) throw std::runtime_error("corpus unreadable");
  bool ok=false; auto corpus=Json::parse(std::string(std::istreambuf_iterator<char>(in),{}),&ok);
  if(!ok || corpus.kind!=Json::Arr) throw std::runtime_error("invalid corpus array");
  std::unique_ptr<ame::Engine> graph;
#ifdef AME_HAS_LMEM
  std::unique_ptr<ame::Mem> lmem;
#else
  if(mode=="lmem") throw std::runtime_error("lmem is not supported by this upstream version");
#endif
#ifdef AME_HAS_CHEAP
  CheapModel cheap;
  if(mode=="graph-cheap") {
   const char* path=std::getenv("AME_CHEAP_MODEL");
   if(!path) throw std::runtime_error("AME_CHEAP_MODEL is required for cheap mode");
   std::ifstream model(path); if(!model) throw std::runtime_error("cheap model unreadable");
   bool valid=false; auto j=Json::parse(std::string(std::istreambuf_iterator<char>(model),{}),&valid);
   if(!valid) throw std::runtime_error("invalid cheap model JSON");
   const auto& fs=required(j,"features"); const auto& ws=required(j,"weights");
   if(fs.kind!=Json::Arr || ws.kind!=Json::Arr || fs.arr.size()!=11 || ws.arr.size()!=11)
    throw std::runtime_error("expected 11 cheap model features and weights");
   for(auto& f:fs.arr)cheap.features.push_back(f.as_str());
   for(auto& w:ws.arr)cheap.weights.push_back(w.as_num());
   cheap.bias=required(j,"bias").as_num();cheap.ok=true;
  }
#else
  if(mode=="graph-cheap") throw std::runtime_error("cheap mode unavailable in this build");
#endif
  std::map<std::string,Json> metadata;
  int words=0; double dict_ms=0;
  if(mode=="graph" || mode=="graph-cheap") {
   graph=std::make_unique<ame::Engine>("");
   if(std::string(argv[2])!="-") {
    auto t=Clock::now();
    words=graph->extractor().load_keyword_dict_cached(argv[2],"",10000000,argv[4]);
    dict_ms=ms(t); if(words<=0) throw std::runtime_error("dictionary failed to load");
   }
  }
#ifdef AME_HAS_LMEM
  else lmem=std::make_unique<ame::Mem>("");
#endif
  // Same timestamp for all documents: do not manufacture relevance from import order.
  auto now=static_cast<ame::TimePoint>(std::time(nullptr));
  auto import_start=Clock::now();
  for(const auto& chunk:corpus.arr) {
   auto text=required(chunk,"content").as_str();
   auto id=required(chunk,"id").as_str();
   if(graph) {
    auto r=graph->remember_turn("knowledge",text,now,id,"yingmu");
    if(r.code!=ame::Err::Ok) throw std::runtime_error("remember failed");
    metadata[r.event_id]=chunk;
   }
#ifdef AME_HAS_LMEM
   else {
    auto r=lmem->remember("yingmu","knowledge","note",text,now,chunk);
    if(r.code!=ame::Err::Ok) throw std::runtime_error("lmem remember failed");
   }
#endif
  }
  Json ready=Json::object(); ready.set("ready",Json::boolean(true));
  ready.set("mode",Json::string(mode));ready.set("chunks",Json::number(corpus.arr.size()));
  ready.set("dictionary_words",Json::number(words));ready.set("dictionary_ms",Json::number(dict_ms));
  ready.set("import_ms",Json::number(ms(import_start)));ready.set("startup_ms",Json::number(ms(start)));
  std::cout<<ready.dump()<<std::endl;
  std::string line;
  while(std::getline(std::cin,line)) {
   try {
    bool valid=false;auto req=Json::parse(line,&valid);if(!valid) throw std::runtime_error("invalid JSON");
    auto query=required(req,"query").as_str();
    int limit=5; if(auto p=req.find("limit"))limit=static_cast<int>(p->as_num());
    if(query.empty() || limit<1 || limit>52) throw std::runtime_error("invalid query or limit");
    Json hits=Json::array();auto t=Clock::now();double recall_ms=0;
    if(graph) {
     ame::SeedQuery sq;sq.text=query;sq.lex_topk=30;sq.soft_min_sim=0.2;
     auto r=graph->recall(sq,{},2,mode=="graph-cheap" ? std::max(limit,60) : limit,false);
#ifdef AME_HAS_CHEAP
     if(mode=="graph-cheap") {cheap_rerank(cheap,query,r.events);if(r.events.size()>static_cast<size_t>(limit))r.events.resize(limit);}
#endif
     recall_ms=ms(t);
     if(r.code!=ame::Err::Ok && r.code!=ame::Err::NotFound) throw std::runtime_error("recall failed");
     for(auto& hit:r.events) if(hit.event) {
      auto item=metadata.at(hit.event->uid);item.set("score",Json::number(hit.score));hits.push(item);
     }
    }
#ifdef AME_HAS_LMEM
    else {
     auto r=lmem->recall(query,"yingmu",limit,now);recall_ms=ms(t);
     for(auto& hit:r) {
      auto obs=lmem->obs(hit.ref_obs); if(!obs)continue;
      auto item=obs->meta;item.set("score",Json::number(hit.score));hits.push(item);
     }
    }
#endif
    Json response=Json::object();response.set("hits",hits);response.set("recall_ms",Json::number(recall_ms));
    std::cout<<response.dump()<<std::endl;
   } catch(const std::exception& e) {Json err=Json::object();err.set("error",Json::string(e.what()));std::cout<<err.dump()<<std::endl;}
  }
 }catch(const std::exception& e) {std::cerr<<e.what()<<std::endl;return 1;}
}

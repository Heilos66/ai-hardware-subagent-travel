// Derived unchanged (except JNI handle-map removal) from arrayforward/ame
// dev-3.0 df68cbf1f2742095901ec97434507d76cba2f61d android/ame_jni/ame_jni.cpp.
// Copyright (c) 2026 arrayforward, MIT; see AME-LICENSE.
#pragma once
#include <algorithm>
#include <cmath>
#include <cctype>
#include <unordered_map>
#include <unordered_set>
namespace {
struct CheapModel {
  std::vector<std::string> features;
  std::vector<double> weights;
  double bias = 0;
  bool ok = false;
};

// 内容词切词：ASCII 小写 len≥3（bench content_words 同口径）
std::vector<std::string> content_words(const std::string& text) {
  std::vector<std::string> words;
  std::string cur;
  auto flush = [&] {
    if (cur.size() >= 3) words.push_back(cur);
    cur.clear();
  };
  for (unsigned char c : text) {
    if (c < 128 && std::isalnum(c)) cur += (char)std::tolower(c);
    else flush();
  }
  flush();
  return words;
}

// 内容词 BM25-lite：df/idf 候选集内统计 log(1+N/(1+df))，Σ idf·tf/(tf+1) 按 idf_sum 归一
std::vector<double> content_scores(const std::string& query,
                                   const std::vector<ame::ScoredEvent>& evs) {
  std::vector<std::string> words = content_words(query);
  std::vector<double> scores(evs.size(), 0.0);
  if (words.empty() || evs.empty()) return scores;
  std::unordered_map<std::string, int> df;
  std::vector<std::unordered_map<std::string, int>> tfs(evs.size());
  for (size_t i = 0; i < evs.size(); ++i) {
    if (evs[i].event) for (auto& w : content_words(evs[i].event->text)) ++tfs[i][w];
    for (auto& w : words)
      if (tfs[i].count(w)) ++df[w];
  }
  double idf_sum = 0;
  std::unordered_map<std::string, double> idf;
  for (auto& w : words) {
    idf[w] = std::log(1.0 + (double)evs.size() / (1.0 + df[w]));
    idf_sum += idf[w];
  }
  for (size_t i = 0; i < evs.size(); ++i) {
    double s = 0;
    for (auto& w : words) {
      auto it = tfs[i].find(w);
      if (it != tfs[i].end()) s += idf[w] * (double)it->second / (it->second + 1.0);
    }
    scores[i] = idf_sum > 0 ? s / idf_sum : 0;
  }
  return scores;
}

// 问题专名 token：大写非句首 len≥2 小写化（bench question_entity_tokens 同口径；
// 中文查询无 ASCII 大写词 → ent_overlap 恒 0，特征自然失效）
std::vector<std::string> question_entity_tokens(const std::string& question) {
  std::vector<std::string> out;
  std::string cur;
  bool sent_start = true;
  auto flush = [&] {
    if (cur.size() >= 2 && !sent_start && std::isupper((unsigned char)cur[0])) {
      std::string low;
      for (char c : cur) low += (char)std::tolower((unsigned char)c);
      out.push_back(low);
    }
    cur.clear();
  };
  for (size_t i = 0; i <= question.size(); ++i) {
    unsigned char c = i < question.size() ? (unsigned char)question[i] : 0;
    if (c < 128 && std::isalnum(c)) {
      cur += (char)c;
    } else {
      flush();
      sent_start = (c == '.' || c == '!' || c == '?' || c == 0);
    }
    if (c == ' ') sent_start = false;
  }
  return out;
}

// cheap_rerank：全池逐候选算 11 特征逻辑分，稳定降序重排（is_entity 端侧无实体
// 通道候选集恒 0——训练数据同恒 0、权重 0，安全外推）
void cheap_rerank(const CheapModel& m, const std::string& query,
                  std::vector<ame::ScoredEvent>& evs) {
  size_t n = evs.size();
  if (n < 2) return;
  std::vector<double> cs = content_scores(query, evs);
  auto minmax = [](double v, double lo, double hi) { return hi > lo ? (v - lo) / (hi - lo) : 0.0; };
  std::vector<double> fus(n), gra(n), ctx(n), tim(n), rec(n);
  double bm_max = 0;
  for (size_t i = 0; i < n; ++i) {
    fus[i] = evs[i].score;
    gra[i] = evs[i].graph_score;
    ctx[i] = evs[i].context_score;
    tim[i] = evs[i].time_score;
    rec[i] = evs[i].event ? -(double)evs[i].event->ts : 0;
    bm_max = std::max(bm_max, cs[i]);
  }
  auto span = [&](const std::vector<double>& v, double& lo, double& hi) {
    lo = hi = v[0];
    for (double x : v) { lo = std::min(lo, x); hi = std::max(hi, x); }
  };
  double f_lo, f_hi, g_lo, g_hi, c_lo, c_hi, t_lo, t_hi, r_lo, r_hi;
  span(fus, f_lo, f_hi); span(gra, g_lo, g_hi); span(ctx, c_lo, c_hi);
  span(tim, t_lo, t_hi); span(rec, r_lo, r_hi);
  std::vector<size_t> ord_bm(n), ord_fu(n);
  for (size_t i = 0; i < n; ++i) ord_bm[i] = ord_fu[i] = i;
  std::stable_sort(ord_bm.begin(), ord_bm.end(), [&](size_t a, size_t b) { return cs[a] > cs[b]; });
  std::stable_sort(ord_fu.begin(), ord_fu.end(), [&](size_t a, size_t b) { return fus[a] > fus[b]; });
  std::vector<double> rank_bm(n), rank_fu(n);
  for (size_t r = 0; r < n; ++r) {
    rank_bm[ord_bm[r]] = 1.0 - (double)r / (double)(n - 1);
    rank_fu[ord_fu[r]] = 1.0 - (double)r / (double)(n - 1);
  }
  if (bm_max <= 0) bm_max = 1.0;
  const std::vector<std::string> q_ents = question_entity_tokens(query);
  std::vector<std::pair<double, size_t>> scored(n);
  for (size_t i = 0; i < n; ++i) {
    std::unordered_set<std::string> ew;
    if (evs[i].event) for (auto& w : content_words(evs[i].event->text)) ew.insert(w);
    double ent_ov = 0.0;
    if (!q_ents.empty()) {
      int hit = 0;
      for (auto& t : q_ents)
        if (ew.count(t)) ++hit;
      ent_ov = (double)hit / (double)q_ents.size();
    }
    std::unordered_map<std::string, double> x = {
        {"bm25", cs[i]},
        {"bm25_rank", rank_bm[i]},
        {"fusion", minmax(fus[i], f_lo, f_hi)},
        {"graph", minmax(gra[i], g_lo, g_hi)},
        {"ctx", minmax(ctx[i], c_lo, c_hi)},
        {"time", minmax(tim[i], t_lo, t_hi)},
        {"fusion_rank", rank_fu[i]},
        {"is_entity", 0.0},
        {"recency", minmax(rec[i], r_lo, r_hi)},
        {"bm25_share", cs[i] / bm_max},
        {"ent_overlap", ent_ov},
    };
    double z = m.bias;
    for (size_t k = 0; k < m.features.size(); ++k) z += m.weights[k] * x[m.features[k]];
    double s = 1.0 / (1.0 + std::exp(-std::max(-30.0, std::min(30.0, z))));
    scored[i] = {s, i};
  }
  std::stable_sort(scored.begin(), scored.end(),
                   [](const auto& a, const auto& b) { return a.first > b.first; });
  std::vector<ame::ScoredEvent> out;
  out.reserve(n);
  for (auto& p : scored) out.push_back(evs[p.second]);
  evs = std::move(out);
}

}

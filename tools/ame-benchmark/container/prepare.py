#!/usr/bin/env python3
"""Create a minimal, secret-free build context from pinned source."""
import argparse,subprocess,shutil,tarfile,io,json,hashlib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
rev='26ce43e2b34be958b84edb4dcf834291ffa820b6'
assert subprocess.check_output(['git','-C',str(a.source),'rev-parse','HEAD'],text=True).strip()==rev
root=Path(__file__).resolve().parents[3];a.out.mkdir(parents=True,exist_ok=False)
for f in ['mvnw','pom.xml']:shutil.copy2(root/f,a.out/f)
for f in ['.mvn','src/main']:shutil.copytree(root/f,a.out/f)
archive=subprocess.check_output(['git','-C',str(a.source),'archive',rev])
with tarfile.open(fileobj=io.BytesIO(archive)) as t:t.extractall(a.out/'upstream',filter='data')
harness=a.out/'harness';harness.mkdir()
for f in ['CMakeLists.txt','worker.cpp','run.py','serve.py']:shutil.copy2(root/'tools/ame-benchmark'/f,harness/f)
(harness/'fixtures').mkdir();shutil.copy2(root/'tools/ame-benchmark/fixtures/corpus.json',harness/'fixtures/corpus.json')
for f in ['Dockerfile','start.py']:shutil.copy2(Path(__file__).parent/f,a.out/f)
manifest={str(f.relative_to(a.out)):hashlib.sha256(f.read_bytes()).hexdigest() for f in a.out.rglob('*') if f.is_file()}
(a.out/'build-inputs.json').write_text(json.dumps(manifest,indent=2));print(a.out)

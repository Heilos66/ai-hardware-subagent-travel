#!/usr/bin/env python3
"""Create a minimal, secret-free build context from pinned source."""
import argparse,subprocess,shutil,tarfile,io,json,hashlib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--version',choices=['2.0','3.0'],default='3.0');a=p.parse_args()
rev=json.loads((Path(__file__).parent.parent/'versions.json').read_text())[a.version]['revision']
assert subprocess.check_output(['git','-C',str(a.source),'rev-parse','HEAD'],text=True).strip()==rev
root=Path(__file__).resolve().parents[3];a.out.mkdir(parents=True,exist_ok=False)
for f in ['mvnw','pom.xml']:shutil.copy2(root/f,a.out/f)
for f in ['.mvn','src/main']:shutil.copytree(root/f,a.out/f)
archive=subprocess.check_output(['git','-C',str(a.source),'archive',rev])
with tarfile.open(fileobj=io.BytesIO(archive)) as t:t.extractall(a.out/'upstream',filter='data')
harness=a.out/'harness';harness.mkdir()
for f in ['CMakeLists.txt','worker.cpp','cheap_rerank.hpp','AME-LICENSE','run.py','serve.py']:shutil.copy2(root/'tools/ame-benchmark'/f,harness/f)
(harness/'fixtures').mkdir();shutil.copy2(root/'tools/ame-benchmark/fixtures/corpus.json',harness/'fixtures/corpus.json')
model=a.out/'upstream/android/app/src/main/assets/cheap_model.json'
(harness/'cheap_model.json').write_bytes(model.read_bytes() if model.exists() else b'{}')
for f in ['Dockerfile','start.py']:shutil.copy2(Path(__file__).parent/f,a.out/f)
dockerfile=a.out/'Dockerfile'
dockerfile.write_text(dockerfile.read_text().replace('df68cbf1f2742095901ec97434507d76cba2f61d',rev))
manifest={str(f.relative_to(a.out)):hashlib.sha256(f.read_bytes()).hexdigest() for f in a.out.rglob('*') if f.is_file()}
(a.out/'build-inputs.json').write_text(json.dumps(manifest,indent=2));print(a.out)

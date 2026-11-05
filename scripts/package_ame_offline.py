#!/usr/bin/env python3
"""Package the standalone AME service and pinned upstream Git history for offline ECS builds."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

REVISION = 'df68cbf1f2742095901ec97434507d76cba2f61d'
SERVICE_FILES = (
    'server.py', 'worker.cpp', 'CMakeLists.txt', 'build.sh', 'install.sh',
    'travel-ame.service', 'AME-LICENSE', 'tests/test_server.py',
)


def git(source, *arguments):
    return subprocess.check_output(['git', '-C', str(source), *arguments], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ame-source', type=Path, required=True)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if git(args.ame_source, 'rev-parse', 'HEAD') != REVISION:
        parser.error('AME source must be at the pinned revision: ' + REVISION)
    if git(args.ame_source, 'status', '--porcelain', '--untracked-files=no'):
        parser.error('Tracked AME source files have local modifications')
    corpus = json.loads(args.corpus.read_text())
    if not isinstance(corpus, list) or not corpus:
        parser.error('Corpus must be a nonempty JSON array')
    ids = set()
    for chunk in corpus:
        if not isinstance(chunk, dict) or any(
            not isinstance(chunk.get(key), str) or not chunk[key].strip()
            for key in ('id', 'content', 'document')
        ):
            parser.error('Each chunk must contain nonempty id, content and document strings')
        if chunk['id'] in ids:
            parser.error('Corpus contains duplicate chunk IDs')
        ids.add(chunk['id'])
    output = args.output.resolve()
    if not output.name.endswith('.tar.gz') or output.exists():
        parser.error('Output must be a new .tar.gz file')
    output.parent.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='ame-package-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'ame-offline'
        stage.mkdir()
        for name in SERVICE_FILES:
            destination = stage / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / 'deploy/ame' / name, destination)
        shutil.copy2(args.corpus, stage / 'corpus.json')
        subprocess.run(['git', '-C', str(args.ame_source), 'bundle', 'create',
                        str(stage / 'ame-upstream.bundle'), 'HEAD'], check=True)
        shutil.copy2(root / 'docs/ame-private-ecs-obs-deployment.md', stage / 'DEPLOYMENT.md')
        (stage / 'manifest.json').write_text(json.dumps({
            'ame_revision': REVISION, 'chunks': len(corpus),
            'documents': sorted({chunk['document'] for chunk in corpus}),
            'corpus_sha256': hashlib.sha256(args.corpus.read_bytes()).hexdigest(),
            'contents': 'Service source, upstream Git bundle, corpus and instructions; no credentials or OS packages',
            'requires': ['Python >= 3.10', 'CMake >= 3.16', 'C++17 compiler', 'make', 'git', 'systemd >= 247'],
        }, ensure_ascii=False, indent=2) + '\n')
        checksums = []
        for path in sorted(stage.rglob('*')):
            if path.is_file():
                checksums.append(hashlib.sha256(path.read_bytes()).hexdigest()
                                 + '  ' + str(path.relative_to(stage)))
        (stage / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
        with tarfile.open(output, 'w:gz') as archive:
            archive.add(stage, arcname='ame-offline')
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    checksum_file = output.with_name(output.name + '.sha256')
    checksum_file.write_text(digest + '  ' + output.name + '\n')
    print(f'Archive: {output}')
    print(f'SHA256: {digest}')
    print(f'Corpus: {len(corpus)} chunks; archive excludes credentials and OS packages')


if __name__ == '__main__':
    main()

"""Build a small server release; never include .env, venv, or Android files."""
import argparse
from contextlib import closing
from pathlib import Path
import sqlite3
import tarfile
import tempfile


def package(root: Path, output: Path, database: Path | None):
    files = [(p, p.relative_to(root).as_posix()) for p in sorted((root / 'server/app').rglob('*.py'))]
    files += [(root / p, p) for p in ('server/requirements.txt', 'server/.env.example', 'server/README.md', 'docs/DEPLOYMENT.md')]
    files += [(p, 'deploy/debian/' + p.name) for p in sorted((root / 'deploy/debian').iterdir()) if p.is_file()]
    for source, _ in files:
        if not source.is_file():
            raise FileNotFoundError(source)
    if database is not None and not database.is_file():
        raise FileNotFoundError(f'Database not found: {database}. Use --without-data for a fresh server.')
    protected = {source.resolve() for source, _ in files}
    if database is not None:
        protected.add(database.resolve())
    if output.resolve() in protected:
        raise ValueError('The output archive must not overwrite a source file or database')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='trainster-release-') as temporary:
        temporary = Path(temporary)
        if database is not None:
            snapshot = temporary / 'trainster.sqlite3'
            with closing(sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True, timeout=30)) as source, closing(sqlite3.connect(snapshot)) as destination:
                source.backup(destination)
                if destination.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                    raise RuntimeError('Database snapshot check failed')
            files.append((snapshot, 'data/trainster.sqlite3'))
        archive_path = temporary / 'release.tar.gz'
        def metadata(info):
            info.uid = info.gid = 0
            info.uname = info.gname = 'root'
            info.mode = 0o600 if info.name == 'data/trainster.sqlite3' else 0o644
            return info
        with tarfile.open(archive_path, 'w:gz') as archive:
            for source, name in files:
                archive.add(source, arcname=name, recursive=False, filter=metadata)
        # Copy only a completed archive; the live SQLite is opened read-only.
        import shutil
        shutil.copyfile(archive_path, output)
    return len(files)


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--without-data', action='store_true', help='Do not migrate existing accounts or trips')
    parser.add_argument('--database', type=Path, default=root / 'server/data/trainster.sqlite3', help='SQLite file actually used by the local server')
    parser.add_argument('--output', type=Path, default=root / 'artifacts/deploy/trainster-server.tar.gz')
    args = parser.parse_args()
    count = package(root, args.output, None if args.without_data else args.database)
    print(f'Created {args.output} ({count} files)')
    print('Database snapshot included.' if not args.without_data else 'Code only; existing accounts and trips are not included.')
    print('API key and local .env are NOT included.')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Publish one universal APK, advance the feed last, then remove older APK assets.
Requires gh authenticated for AndreDon0/rr-vpn and a clean, pushed main checkout.
APK signing stays local. Never upload a keystore, credential, or private subscription.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

REPO = 'AndreDon0/rr-vpn'
CERT = '42de7d1c946702f6ea9117b1bd4f9ba5167ef796342f1cab08408546c618da19'

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--notes-file', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    os.chdir(root)
    if run('git', 'branch', '--show-current') != 'main' or run('git', 'status', '--porcelain'):
        raise SystemExit('Use a clean main checkout; commit and push release code before publishing.')
    if run('git', 'rev-parse', 'HEAD') != run('git', 'rev-parse', '@{upstream}'):
        raise SystemExit('Push main first.')
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sdk / 'build-tools' / '35.0.0'
    apk = args.apk.resolve()
    signature = run(str(tools / 'apksigner'), 'verify', '--print-certs', str(apk))
    if CERT not in signature:
        raise SystemExit('The APK must use the original private signing identity.')
    badging = run(str(tools / 'aapt'), 'dump', 'badging', str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    minimum = re.search(r"sdkVersion:'([0-9]+)'", badging)
    if not package or package[1] != 'com.dream.driftpool' or not minimum:
        raise SystemExit('Unexpected Android package.')
    code, version, min_sdk = int(package[2]), package[3], int(minimum[1])
    if not re.fullmatch(r'[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}', version):
        raise SystemExit('Expected a stable x.y.z version.')
    if apk.name != f'RR-VPN-{version}-universal.apk':
        raise SystemExit('Use the versioned universal APK filename.')
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
        if abis != {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}:
            raise SystemExit('Only a four-architecture universal APK can be published.')
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    size = apk.stat().st_size
    if size > 100 * 1024 * 1024:
        raise SystemExit('APK exceeds updater size limit.')
    tag = 'v' + version
    # A draft is not visible to the updater. Complete all uploads before publishing it.
    with tempfile.TemporaryDirectory(prefix='rr-vpn-release-') as temporary:
        checksum = Path(temporary) / (apk.name + '.sha256')
        checksum.write_text(f'{sha}  {apk.name}\n')
        subprocess.run(['gh', 'release', 'create', tag, str(apk), str(checksum), '--repo', REPO,
                        '--target', run('git', 'rev-parse', 'HEAD'), '--title', f'RR VPN {version}',
                        '--notes-file', str(args.notes_file.resolve()), '--draft'], check=True)
        subprocess.run(['gh', 'release', 'edit', tag, '--repo', REPO, '--draft=false', '--latest'], check=True)
    feed = root / 'updates' / 'stable.json'
    feed.parent.mkdir(exist_ok=True)
    feed.write_text(json.dumps({'schema': 1, 'versionCode': code, 'versionName': version, 'minSdk': min_sdk,
                               'size': size, 'sha256': sha, 'url': f'https://github.com/{REPO}/releases/download/{tag}/{apk.name}'}, indent=2) + '\n')
    subprocess.run(['git', 'add', 'updates/stable.json'], check=True)
    subprocess.run(['git', 'commit', '-m', f'Publish {version} update feed'], check=True)
    subprocess.run(['git', 'push', 'origin', 'main'], check=True)
    # Run this only after the new release AND feed are published. Retain source tags/release notes.
    releases = json.loads(run('gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100'))
    for page in releases:
        for release in page:
            if release['tag_name'] == tag:
                continue
            for asset in release.get('assets', []):
                if asset['name'].endswith(('.apk', '.apk.sha256')):
                    subprocess.run(['gh', 'api', '--method', 'DELETE', f"repos/{REPO}/releases/assets/{asset['id']}"], check=True)
    print(f'https://github.com/{REPO}/releases/tag/{tag}')

if __name__ == '__main__':
    main()

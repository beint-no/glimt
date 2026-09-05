"""Canonical, timestamp-independent verification of pinned Gitiles archives."""
import hashlib
import json
import tarfile


def tree_hash(archive):
    # Read compressed content in archive order, then sort only the small hash
    # records. Seeking to alphabetically sorted members can decompress the same
    # gzip data repeatedly. Keep the existing canonical digest format unchanged.
    entries = []
    with tarfile.open(archive, mode='r|*') as tar:
        for item in tar:
            if not (item.isfile() or item.isdir() or item.issym() or item.islnk()):
                raise RuntimeError('Unexpected source archive member')
            digest = None
            if item.isfile():
                with tar.extractfile(item) as stream:
                    content = hashlib.sha256()
                    while block := stream.read(1 << 20):
                        content.update(block)
                    digest = content.hexdigest()
            entries.append([item.name, item.type.decode('ascii'), item.mode, item.linkname, digest])
    entries.sort(key=lambda entry: entry[0])
    return hashlib.sha256(json.dumps(entries, separators=(',', ':')).encode()).hexdigest()

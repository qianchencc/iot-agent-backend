#!/usr/bin/env python3
"""Fetch a pinned linux/amd64 registry image into a Docker 1.10+ load archive.

No daemon/proxy configuration is changed. Public Docker Hub pull tokens stay in
memory; manifest, config, compressed layers and uncompressed diff IDs are checked.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def fetch_image(repository, digest, tag, output):
    base = "https://registry-1.docker.io/v2/" + repository
    token_url = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:" + repository + ":pull"
    with urlopen(token_url, timeout=30) as response:
        token = json.load(response)["token"]

    def download(path, destination, expected):
        request = Request(base + path, headers={"Authorization": "Bearer " + token,
            "Accept": "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"})
        checksum = hashlib.sha256()
        with urlopen(request, timeout=120) as response, destination.open("wb") as target:
            while chunk := response.read(1024 * 1024):
                checksum.update(chunk)
                target.write(chunk)
        if "sha256:" + checksum.hexdigest() != expected:
            raise ValueError("Registry content checksum mismatch")

    output = Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="image-", dir=output.parent) as temporary:
        root = Path(temporary)
        manifest_path = root / "manifest.registry.json"
        download("/manifests/" + digest, manifest_path, digest)
        manifest = json.loads(manifest_path.read_text())
        config_digest = manifest["config"]["digest"]
        config_name = config_digest.removeprefix("sha256:") + ".json"
        download("/blobs/" + config_digest, root / config_name, config_digest)
        config = json.loads((root / config_name).read_text())
        if config.get("architecture") != "amd64" or config.get("os") != "linux":
            raise ValueError("Only a resolved linux/amd64 image manifest is accepted")
        layers = manifest["layers"]
        diff_ids = config["rootfs"]["diff_ids"]
        if len(layers) != len(diff_ids):
            raise ValueError("Layer/config count mismatch")

        def layer(index):
            descriptor = layers[index]
            name = descriptor["digest"].removeprefix("sha256:")
            compressed = root / (name + ".blob")
            download("/blobs/" + descriptor["digest"], compressed, descriptor["digest"])
            directory = root / name
            directory.mkdir()
            target = directory / "layer.tar"
            media = descriptor["mediaType"]
            opener = gzip.open if "gzip" in media else open
            if "zstd" in media:
                raise ValueError("Selected image uses zstd; select a gzip layer image for Docker 17")
            checksum = hashlib.sha256()
            with opener(compressed, "rb") as source, target.open("wb") as dest:
                while chunk := source.read(1024 * 1024):
                    checksum.update(chunk)
                    dest.write(chunk)
            if "sha256:" + checksum.hexdigest() != diff_ids[index]:
                raise ValueError("Uncompressed layer checksum mismatch")
            compressed.unlink()
            return name + "/layer.tar"

        with ThreadPoolExecutor(max_workers=4) as executor:
            names = list(executor.map(layer, range(len(layers))))
        archive_manifest = [{"Config": config_name, "RepoTags": [tag], "Layers": names}]
        manifest_bytes = json.dumps(archive_manifest).encode()
        partial = output.with_name(output.name + ".partial")
        try:
            with partial.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=1, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w|") as archive:
                    archive.add(root / config_name, arcname=config_name)
                    for name in names:
                        archive.add(root / name, arcname=name)
                    info = tarfile.TarInfo("manifest.json")
                    info.size = len(manifest_bytes)
                    info.mode = 0o644
                    archive.addfile(info, io.BytesIO(manifest_bytes))
            partial.replace(output)
        finally:
            partial.unlink(missing_ok=True)
    checksum = hashlib.sha256()
    with output.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            checksum.update(chunk)
    return {"tag": tag, "imageId": config_digest, "manifestDigest": digest,
            "archiveSha256": checksum.hexdigest(), "archiveBytes": output.stat().st_size,
            "architecture": "amd64", "os": "linux"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="Docker Hub repository, e.g. library/node")
    parser.add_argument("--digest", required=True, help="Resolved platform manifest sha256")
    parser.add_argument("--tag", required=True, help="Deployment-owned local image tag")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = fetch_image(args.repository, args.digest, args.tag, args.output)
    except Exception as error:
        # Redirected registry URLs can contain signed credentials; do not log them.
        parser.exit(1, "Image export failed: " + type(error).__name__ +
                    (" HTTP " + str(error.code) if isinstance(error, HTTPError) else "") + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()

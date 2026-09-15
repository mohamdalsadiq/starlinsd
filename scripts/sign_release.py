"""Sign a verified unsigned CI bundle using the owner's existing private kit.

Usage: python scripts/sign_release.py bundle.zip private-kit-directory output.apk
The private kit must already be recovered from the owner's private backup.
"""
import hashlib
import os
import pathlib
import re
import ssl
import subprocess
import sys
import tempfile
import zipfile


def run(*args, env=None):
    return subprocess.check_output(args, env=env, text=True, stderr=subprocess.STDOUT)


def sign(bundle, kit, output):
    kit = pathlib.Path(kit).resolve()
    output = pathlib.Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    key = kit / "slotra-release.p12"
    password = kit / "signing-password.txt"
    cert = kit / "slotra-certificate.pem"
    for path in (key, password, cert):
        if not path.is_file():
            raise ValueError("Recover the existing private signing kit; do not generate a replacement key")
    expected = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
    with tempfile.TemporaryDirectory(prefix="slotra-sign-") as directory:
        root = pathlib.Path(directory)
        required = ["slotra-unsigned.apk", "apksigner.jar", "zipalign", "lib64/libc++.so"]
        with zipfile.ZipFile(bundle) as archive:
            for name in required:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.read(name))
        align = root / "zipalign"
        align.chmod(0o700)
        env = dict(os.environ)
        env["LD_LIBRARY_PATH"] = str(root / "lib64")
        aligned = root / "aligned.apk"
        run(str(align), "-P", "16", "-f", "4", str(root / "slotra-unsigned.apk"), str(aligned), env=env)
        temporary = root / "signed.apk"
        run("java", "-jar", str(root / "apksigner.jar"), "sign", "--ks", str(key), "--ks-key-alias", "upload",
            "--ks-pass", f"file:{password}", "--out", str(temporary), str(aligned))
        verification = run("java", "-jar", str(root / "apksigner.jar"), "verify", "--verbose", "--print-certs", str(temporary))
        found = re.search(r"Signer #1 certificate SHA-256 digest: ([a-f0-9]+)", verification)
        if not found or found.group(1) != expected:
            raise ValueError("The APK signing certificate does not match the saved release key")
        run(str(align), "-c", "-P", "16", "4", str(temporary), env=env)
        # Signing must not change application code, resources or manifest.
        with zipfile.ZipFile(root / "slotra-unsigned.apk") as before, zipfile.ZipFile(temporary) as after:
            for name in before.namelist():
                if not name.startswith("META-INF/") and not name.endswith("/"):
                    if hashlib.sha256(before.read(name)).digest() != hashlib.sha256(after.read(name)).digest():
                        raise ValueError(f"Application entry changed during signing: {name}")
        output.write_bytes(temporary.read_bytes())
        output.with_suffix(".verification.txt").write_text(verification + "\nZIP alignment: passed (16 KiB pages)\nApplication entries: unchanged\nSHA256: " + hashlib.sha256(output.read_bytes()).hexdigest() + "\n")
        print(verification)
        print("Signed APK:", output)
        print("SHA256:", hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: sign_release.py bundle.zip private-kit-directory output.apk")
    sign(*sys.argv[1:])

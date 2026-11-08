"""Copy the app launcher into a verification output directory.

start-apps.sh calls check-image-names.sh from its own directory before starting anything (decision 118),
so the copy carries both scripts.
"""

from pathlib import Path
import shutil

SCRIPTS = ("start-apps.sh", "check-image-names.sh")


def copy_launcher(root: Path, output: Path) -> Path:
    for name in SCRIPTS:
        shutil.copyfile(root / "scripts" / name, output / name)
    return output / SCRIPTS[0]

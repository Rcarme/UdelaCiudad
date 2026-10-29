import sys
from pathlib import Path

# Ensure server/app is the first entry in sys.path so tests always import the production modules
APP_DIR = Path(__file__).resolve().parent.parent / "app"
if str(APP_DIR) not in sys.path:
    sys.path.insert(0, str(APP_DIR))

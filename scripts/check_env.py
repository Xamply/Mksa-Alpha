import sys
import os

print(f"Python version: {sys.version}")
print(f"Current working dir: {os.getcwd()}")
try:
    import PIL
    print(f"Pillow is installed, version: {PIL.__version__}")
except ImportError:
    print("Pillow is NOT installed.")

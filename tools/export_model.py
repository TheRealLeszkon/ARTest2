#!/usr/bin/env python3
"""
Regenerate the object-detection model asset for Milestone 3.

The ONNX weights are git-ignored (see .gitignore) so they don't bloat the repo. Run
this from the Yolo-test venv (which has ultralytics) to recreate them:

    /path/to/Yolo-test/.venv/bin/python tools/export_model.py

It exports the standard YOLO11n-seg (80 COCO classes) to a static-shape 640x640 ONNX
graph and writes it + a labels file into app/src/main/assets/.

Model layout the Kotlin decoder (YoloSegDetector.kt) expects:
  input  images   [1, 3, 640, 640]  (RGB, CHW, /255)
  output0         [1, 116, 8400]     4 box + 80 class + 32 mask-coeff, feature-major
  output1         [1, 32, 160, 160]  mask prototypes (used from Milestone 4)
"""
import os
from ultralytics import YOLO

# Source weights inside the sibling Yolo-test project. Adjust if you keep them elsewhere.
PT = os.environ.get(
    "YOLO_SEG_PT",
    "../Yolo-test/models/yolo11seg/yolo11n-seg.pt",
)
ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")

model = YOLO(PT)
onnx_path = model.export(format="onnx", opset=17, imgsz=640, simplify=True, dynamic=False)

os.makedirs(ASSETS, exist_ok=True)
dst = os.path.join(ASSETS, "yolo11n-seg.onnx")
os.replace(onnx_path, dst)

names = [model.names[i] for i in range(len(model.names))]
with open(os.path.join(ASSETS, "coco_labels.txt"), "w") as f:
    f.write("\n".join(names) + "\n")

print(f"Wrote {dst} and coco_labels.txt ({len(names)} classes)")

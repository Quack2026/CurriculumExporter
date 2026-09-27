from PIL import Image
src = r"C:\Tools\icon.png"
dst = r"C:\Tools\CurriculumExporter\app.ico"
img = Image.open(src).convert("RGBA")
sizes = [(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)]
img.save(dst, format="ICO", sizes=sizes)
im2 = Image.open(dst)
print("   sizes in ico:", sorted(im2.info.get("sizes", [])))
import os
print("   ico bytes:", os.path.getsize(dst))

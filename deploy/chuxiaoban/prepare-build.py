from pathlib import Path
import sys, zipfile, json, shutil
root = Path(sys.argv[1]).resolve()
archive = Path(sys.argv[2]).resolve()
with zipfile.ZipFile(archive) as z:
    for item in z.infolist():
        target = (root / item.filename).resolve()
        if root not in target.parents: raise ValueError("Unsafe archive entry")
    z.extractall(root)
web = root / "chuxiaoban/apps/web-mini"
manifest = web / "src/manifest.json"
data = json.loads(manifest.read_text(encoding="utf-8"))
data["h5"]["router"]["base"] = "/chuxiaoban/"
manifest.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
vite = web / "vite.config.ts"
s = vite.read_text(encoding="utf-8").replace("plugins: [uni()]", "base: '/chuxiaoban/', plugins: [uni()]")
vite.write_text(s, encoding="utf-8")
# uni-app copies src/static; this project keeps its original assets at static/.
shutil.copytree(web / "static", web / "src/static", dirs_exist_ok=True)
print("Prepared deployment copy; original project files unchanged")

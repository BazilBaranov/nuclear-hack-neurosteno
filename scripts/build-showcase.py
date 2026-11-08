#!/usr/bin/env python3
"""Export the public project showcase; never copy APKs or application sources."""
import argparse
import html
import os
from pathlib import Path
import re
import shutil
from urllib.parse import urlsplit

import markdown
from markdown.extensions.toc import slugify_unicode

ROOT = Path(__file__).resolve().parents[1]
REPO = "https://github.com/BazilBaranov/nuclear-hack-neurosteno/blob/main/"
DOCS = ["INSTALLATION", "ARCHITECTURE", "MODELS", "PROTOCOL", "LIMITATIONS",
        "TEST_RESULTS", "VALIDATION", "TESTING", "speech-model-licenses"]


def build(output):
    output.mkdir(parents=True, exist_ok=True)
    documents = {ROOT / "docs" / (name + ".md"): output / (name + ".html") for name in DOCS}
    for folder in ["demo", "test-results", "apk"]:
        source = ROOT / "artifacts" / folder
        for path in source.iterdir():
            if path.suffix in {".md", ".json", ".csv", ".txt", ".mp4"}:
                target = output / "artifacts" / folder / path.name
                target.parent.mkdir(parents=True, exist_ok=True)
                if path.suffix == ".md":
                    documents[path] = target.with_suffix(".html")
                else:
                    shutil.copy2(path, target)
    shutil.copytree(ROOT / "docs/assets", output / "assets", dirs_exist_ok=True)

    def rewrite(content, source, target):
        def link(match):
            attr, value = match.groups()
            parsed = urlsplit(html.unescape(value))
            if parsed.scheme or parsed.netloc or not parsed.path:
                return match.group(0)
            local = (source.parent / parsed.path).resolve()
            dest = documents.get(local)
            if not dest and local.is_relative_to(ROOT / "docs/assets"):
                dest = output / "assets" / local.relative_to(ROOT / "docs/assets")
            if not dest and local.is_relative_to(ROOT / "artifacts") and local.suffix != ".apk":
                candidate = output / local.relative_to(ROOT)
                if candidate.is_file():
                    dest = candidate
                elif candidate / "README.html" in documents.values():
                    dest = candidate / "README.html"
            if not dest and local == ROOT / "README.md":
                dest = output / "index.html"
            if dest:
                value = os.path.relpath(dest, target.parent)
            elif local.is_relative_to(ROOT):
                value = REPO + local.relative_to(ROOT).as_posix()
            else:
                raise ValueError(f"Link outside repository: {source}: {value}")
            if parsed.fragment:
                value += "#" + parsed.fragment
            return f'{attr}="{html.escape(value, quote=True)}"'
        return re.sub(r'(href|src)="([^"]+)"', link, content)

    source = ROOT / "docs/index.html"
    page = source.read_text()
    css = re.search(r"<style>(.*?)</style>", page, re.S).group(1)
    (output / "index.html").write_text(rewrite(page, source, output / "index.html"))
    for source, target in documents.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        text = source.read_text()
        title = text.splitlines()[0].lstrip("# ")
        body = markdown.markdown(text, extensions=["tables", "fenced_code", "toc"],
                                 extension_configs={"toc": {"slugify": slugify_unicode}})
        body = rewrite(body, source, target)
        home = os.path.relpath(output / "index.html", target.parent)
        target.write_text(f'<!doctype html><html lang="ru"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1"><title>{html.escape(title)} · НейроСтенограф</title>'
            f'<style>{css}\narticle{{max-width:850px;margin:40px auto 80px;overflow-wrap:anywhere}}'
            'article h1{font-size:38px}article h2{margin-top:38px}article pre{background:var(--soft);padding:18px;border-radius:6px}'
            'article table{display:block;overflow:auto}article img{max-width:100%}</style></head><body><div class="wrap">'
            f'<header><a class="brand" href="{home}">НейроСтенограф</a><a href="{home}#materials">Все материалы →</a></header>'
            f'<main><article>{body}</article></main></div></body></html>')
    assert not list(output.rglob("*.apk")), "APKs must remain in Releases"
    print(f"Built showcase and {len(documents)} documentation pages in {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    build(parser.parse_args().output.resolve())

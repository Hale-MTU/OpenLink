#!/usr/bin/env python3
"""Build the GitHub wiki from the repository's Markdown.

The repository is the source: README.md becomes Home, every other Markdown
file at the root and every file under docs/ becomes one page, and a sidebar
groups them by folder. The wiki is flat and links by page name, so each relative link
is rewritten: to the page it points at, or, for anything else in the
repository, to the file on GitHub. Pages whose source is gone are removed.

  scripts/sync-wiki.py --repo OWNER/NAME --out path/to/wiki-checkout
  scripts/sync-wiki.py --repo OWNER/NAME --check

--out is run by .github/workflows/wiki.yml on every push to main that touches
Markdown. --check, run by verify.yml, builds nothing and fails on a relative
link to a file that does not exist, which the wiki would turn into a 404.

The same script runs in OpenVitals, OpenWatch, OpenLink and OpenBridge; keep
the copies alike.
"""

from __future__ import annotations

import argparse
import dataclasses
import pathlib
import posixpath
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Files whose page name is fixed, and the name is the title too; any other
# file is named after its first heading.
FIXED_PAGES = {
    "README.md": "Home",
    "CHANGELOG.md": "Changelog",
    "PRIVACY.md": "Privacy-Policy",
    "AGENTS.md": "Agents",
    # OpenVitals: the detailed inventory, beside docs/features/README.md's "Features" index.
    "Features.md": "Feature-Inventory",
    # OpenLink: its one doc, whose heading is a sentence.
    "docs/spec.md": "Specification",
}
# The sidebar's order for docs/ folders; one not listed comes after, by name.
SECTION_ORDER = ["features", "how-to", "app", "reference", "engineering", "proposals", "releases"]
SECTION_TITLES = {"how-to": "How-to"}
# Sections listed newest first: release notes named by version.
NEWEST_FIRST = {"releases"}
# Wiki files this script owns. Anything else in the wiki (an uploaded image) is kept.
GENERATED = {"_Sidebar.md", "_Footer.md"}

LINK = re.compile(r"(!?)\[([^\]]*)\]\(([^)\s]+)((?:\s+\"[^\"]*\")?)\)")
REFERENCE = re.compile(r"^(\s*\[[^\]]+\]:\s*)(\S+)(.*)$")
FENCE = re.compile(r"^\s*(```|~~~)")
H1 = re.compile(r"^#\s+(.+?)\s*#*\s*$")


@dataclasses.dataclass
class Page:
    source: str  # repository path, posix
    name: str  # wiki page name, the file name without .md
    title: str
    section: str  # docs/ folder, or "" for a root file


def page_name(title: str) -> str:
    """'Sleep minute features (SM line, version 3)' -> 'Sleep-minute-features-SM-line-version-3'."""
    words = re.sub(r"[^A-Za-z0-9. -]", " ", title).split()
    return "-".join(words) or "Page"


def first_heading(text: str) -> str | None:
    for line in text.splitlines():
        match = H1.match(line)
        if match:
            return match.group(1).strip()
    return None


def collect(root: pathlib.Path) -> list[Page]:
    pages: list[Page] = []
    roots = sorted(root.glob("*.md"), key=lambda p: (p.name != "README.md", p.name.lower()))
    for path in roots:
        heading = first_heading(path.read_text(encoding="utf-8"))
        name = FIXED_PAGES.get(path.name)
        if name is None:
            name = page_name(heading) if heading and not heading.endswith(".md") else page_name(path.stem.replace("_", " ").title())
        # A fixed name is the title too ("# AGENTS.md" names the file, not the page).
        fixed = path.name in FIXED_PAGES and path.name != "README.md"
        title = heading if heading and not heading.endswith(".md") and not fixed else name.replace("-", " ")
        pages.append(Page(path.name, name, title, ""))
    for path in sorted((root / "docs").rglob("*.md")):
        source = path.relative_to(root).as_posix()
        title = first_heading(path.read_text(encoding="utf-8")) or path.stem
        name = FIXED_PAGES.get(source) or page_name(title)
        if source in FIXED_PAGES:
            title = name.replace("-", " ")
        section = source.split("/")[1] if source.count("/") >= 2 else ""
        pages.append(Page(source, name, title, section))
    seen: dict[str, str] = {}
    for page in pages:
        if page.name in seen:
            sys.exit(f"sync-wiki: {page.source} and {seen[page.name]} both become the page {page.name}; retitle one")
        seen[page.name] = page.source
    return pages


class Rewriter:
    def __init__(self, root: pathlib.Path, repo: str, branch: str, pages: list[Page]):
        self.root = root
        self.base = f"https://github.com/{repo}"
        self.branch = branch
        self.by_source = {page.source: page for page in pages}
        self.broken: list[tuple[str, str]] = []

    def target(self, source: str, target: str, image: bool) -> str:
        if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I) or target.startswith(("#", "//")):
            return target  # absolute URL, mailto:, or an anchor on the same page
        path, _, anchor = target.partition("#")
        if path.startswith("/"):
            resolved = path.lstrip("/")
        else:
            resolved = posixpath.normpath(posixpath.join(posixpath.dirname(source), path))
        fragment = f"#{anchor}" if anchor else ""
        page = self.by_source.get(resolved)
        if page and not image:
            return page.name + fragment
        if resolved.startswith("../"):
            return target  # outside the repository: leave it as written
        if not (self.root / resolved).exists():
            self.broken.append((source, target))
        kind = "raw" if image else ("tree" if (self.root / resolved).is_dir() else "blob")
        return f"{self.base}/{kind}/{self.branch}/{resolved}{fragment}"

    def rewrite(self, source: str, text: str) -> str:
        out: list[str] = []
        fenced = False
        for line in text.splitlines():
            if FENCE.match(line):
                fenced = not fenced
                out.append(line)
                continue
            if fenced:
                out.append(line)
                continue
            line = LINK.sub(
                lambda m: f"{m.group(1)}[{m.group(2)}]({self.target(source, m.group(3), bool(m.group(1)))}{m.group(4)})",
                line,
            )
            ref = REFERENCE.match(line)
            if ref:
                line = ref.group(1) + self.target(source, ref.group(2), False) + ref.group(3)
            out.append(line)
        return "\n".join(out) + "\n"


def body(page: Page, text: str) -> str:
    """Drops the first H1: the wiki shows the page name as its title already."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if H1.match(line):
            del lines[i]
            if i < len(lines) and not lines[i].strip():
                del lines[i]
            break
    return "\n".join(lines).strip("\n") + "\n"


def natural(page: Page) -> list:
    """Sorts "2.10.0" after "2.9.0"."""
    return [int(part) if part.isdigit() else part for part in re.split(r"(\d+)", page.title.lower())]


def sidebar(pages: list[Page]) -> str:
    lines = ["**[[Home]]**", ""]
    sections = sorted(
        {page.section for page in pages if page.section},
        key=lambda s: (SECTION_ORDER.index(s) if s in SECTION_ORDER else len(SECTION_ORDER), s),
    )
    for section in sections:
        lines.append(f"**{SECTION_TITLES.get(section, section.replace('-', ' ').capitalize())}**")
        listed = sorted((p for p in pages if p.section == section), key=natural, reverse=section in NEWEST_FIRST)
        # A folder's README is its index: first.
        listed.sort(key=lambda p: not p.source.endswith("/README.md"))
        for page in listed:
            lines.append(f"- [[{page.title}|{page.name}]]")
        lines.append("")
    lines.append("**Project**")
    for page in pages:
        if not page.section and page.name != "Home":
            lines.append(f"- [[{page.title}|{page.name}]]")
    return "\n".join(lines) + "\n"


def build(root: pathlib.Path, repo: str, branch: str, out: pathlib.Path) -> tuple[list[str], list[tuple[str, str]]]:
    pages = collect(root)
    rewriter = Rewriter(root, repo, branch, pages)
    written = set(GENERATED)
    for page in pages:
        text = (root / page.source).read_text(encoding="utf-8")
        content = rewriter.rewrite(page.source, body(page, text))
        source_url = f"https://github.com/{repo}/blob/{branch}/{page.source}"
        content += f"\n---\n_Generated from [`{page.source}`]({source_url}). Edit it there; changes made here are overwritten._\n"
        (out / f"{page.name}.md").write_text(content, encoding="utf-8")
        written.add(f"{page.name}.md")
    (out / "_Sidebar.md").write_text(sidebar(pages), encoding="utf-8")
    (out / "_Footer.md").write_text(
        f"This wiki mirrors the Markdown in [{repo}](https://github.com/{repo}) on every push to `{branch}`.\n",
        encoding="utf-8",
    )
    removed = []
    for stale in out.glob("*.md"):
        if stale.name not in written:
            stale.unlink()
            removed.append(stale.name)
    return removed, rewriter.broken


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", required=True, help="owner/name, for links back to the repository")
    parser.add_argument("--branch", default="main")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--out", type=pathlib.Path, help="the wiki checkout to write into")
    target.add_argument("--check", action="store_true", help="only fail on links to missing files")
    args = parser.parse_args()
    if args.check:
        with tempfile.TemporaryDirectory() as scratch:
            _, broken = build(ROOT, args.repo, args.branch, pathlib.Path(scratch))
    else:
        args.out.mkdir(parents=True, exist_ok=True)
        removed, broken = build(ROOT, args.repo, args.branch, args.out)
        for name in removed:
            print(f"removed {name}")
    for source, link in broken:
        print(f"::error file={source}::link to a file that does not exist: {link}")
    if broken:
        return 1
    print("wiki: every link resolves" if args.check else f"wiki pages written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

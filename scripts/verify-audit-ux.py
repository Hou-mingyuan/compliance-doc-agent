#!/usr/bin/env python3
"""Run a small authenticated browser layout and console smoke."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
VIEWPORTS = {
    "desktop-1440x900": {"width": 1440, "height": 900},
    "tablet-768x1024": {"width": 768, "height": 1024},
    "mobile-375x812": {"width": 375, "height": 812},
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("WORKBENCH_BASE", "http://127.0.0.1:19070"))
    parser.add_argument("--output-dir", type=Path, default=ROOT / "output/playwright/ux-smoke")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    failures: list[str] = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        for name, viewport in VIEWPORTS.items():
            page = browser.new_page(viewport=viewport)
            console_issues: list[str] = []
            page.on(
                "console",
                lambda message, issues=console_issues: issues.append(f"{message.type}: {message.text}")
                if message.type in {"error", "warning"}
                else None,
            )
            page.goto(args.base, wait_until="networkidle")
            page.locator('input[autocomplete="username"]').fill("reviewer@demo.local")
            page.locator('input[autocomplete="current-password"]').fill("demo-change-me")
            page.get_by_role("button", name="登录审核台").click()
            page.wait_for_url("**/#/documents")
            page.get_by_role("link", name="审核").first.click()
            page.wait_for_url("**/#/reviews")
            page.wait_for_load_state("networkidle")

            page.get_by_role("heading", name="审核运行").wait_for()
            overflow = page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth")
            if overflow > 1:
                failures.append(f"{name}: horizontal overflow {overflow}px")
            failures.extend(f"{name}: {issue}" for issue in console_issues)
            page.screenshot(path=str(args.output_dir / f"{name}.png"), full_page=True)
            page.close()
        browser.close()

    print(f"viewports={len(VIEWPORTS)} failures={len(failures)} screenshots={args.output_dir}")
    for failure in failures:
        print(failure)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

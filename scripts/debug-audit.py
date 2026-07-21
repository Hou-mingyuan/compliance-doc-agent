#!/usr/bin/env python3
"""Capture a current authenticated UI snapshot for local debugging."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("WORKBENCH_BASE", "http://127.0.0.1:19070"))
    parser.add_argument("--output", type=Path, default=ROOT / "output/playwright/debug-audit.png")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        console_errors: list[str] = []
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.on("response", lambda response: print(f"HTTP {response.status} {response.url}") if "/api/" in response.url else None)

        page.goto(args.base, wait_until="networkidle")
        page.locator('input[autocomplete="username"]').fill("reviewer@demo.local")
        page.locator('input[autocomplete="current-password"]').fill("demo-change-me")
        page.get_by_role("button", name="登录审核台").click()
        page.wait_for_url("**/#/documents")
        page.get_by_role("link", name="审核").first.click()
        page.wait_for_url("**/#/reviews")
        page.wait_for_load_state("networkidle")

        print("title:", page.title())
        print("url:", page.url)
        print("reviews:", page.locator("tbody tr").count())
        print("console errors:", console_errors)
        page.screenshot(path=str(args.output), full_page=True)
        browser.close()
    return 1 if console_errors else 0


if __name__ == "__main__":
    raise SystemExit(main())

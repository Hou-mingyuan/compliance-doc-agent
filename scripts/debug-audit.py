from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    errors = []
    page.on("console", lambda m: errors.append(f"{m.type}:{m.text}") if m.type == "error" else None)
    page.on("response", lambda r: print(f"HTTP {r.status} {r.url}") if "/api/" in r.url else None)

    page.goto("http://localhost:5173/#/report?documentId=1&filename=合同条款片段.txt")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(5000)
    print("title:", page.title())
    print("phase:", page.locator(".phase-step.active").all_text_contents())
    print("log items:", page.locator(".event-log li").count())
    if page.locator(".event-log li").count():
        print("last log:", page.locator(".event-log li").last.inner_text())
    print("findings:", page.locator(".finding-item").count())
    print("marks:", page.locator(".doc-preview mark.hl").count())
    print("narrative len:", len(page.locator(".narrative-scroll p").inner_text().strip()))
    print("body snippet:", page.locator(".workbench").inner_text()[:500])
    print("console errors:", errors)
    page.screenshot(path="d:/project-hub/compliance-doc-agent/docs/screenshots/debug.png", full_page=True)
    browser.close()

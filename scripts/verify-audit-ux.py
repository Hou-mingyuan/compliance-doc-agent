"""Verify compliance-doc-agent audit page diff highlight + SSE UX."""
from pathlib import Path
from playwright.sync_api import sync_playwright

OUT = Path(__file__).resolve().parent.parent / "docs" / "screenshots"
OUT.mkdir(parents=True, exist_ok=True)

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    errors: list[str] = []

    def on_console(msg):
        if msg.type == "error":
            errors.append(msg.text)

    page.on("console", on_console)
    page.goto("http://localhost:5173/#/report?documentId=1&filename=合同条款片段.txt")
    page.wait_for_load_state("networkidle")

    # Mid-stream: rules phase
    try:
        page.wait_for_selector(".phase-step.active", timeout=15000)
        page.screenshot(path=str(OUT / "audit-sse-streaming.png"), full_page=True)
    except Exception:
        page.screenshot(path=str(OUT / "audit-sse-streaming.png"), full_page=True)

    # Wait for completion (done event in log or summary visible)
    page.wait_for_selector(".ev-done, .inline-summary", timeout=90000)
    page.wait_for_timeout(1000)
    page.screenshot(path=str(OUT / "audit-diff-highlight-done.png"), full_page=True)

    # Click first finding to verify scroll/highlight
    finding = page.locator(".finding-item").first
    if finding.count():
        finding.click()
        page.wait_for_timeout(500)
        page.screenshot(path=str(OUT / "audit-finding-active.png"), full_page=True)

    marks = page.locator(".doc-preview mark.hl").count()
    inserts = page.locator(".doc-preview .diff-insert").count()
    events = page.locator(".event-log li").count()
    narrative = page.locator(".narrative-scroll p").inner_text().strip()

    print(f"console_errors={len(errors)}")
    print(f"highlight_marks={marks}")
    print(f"missing_inserts={inserts}")
    print(f"sse_events={events}")
    print(f"narrative_len={len(narrative)}")
    print(f"screenshots={OUT}")

    if errors:
        print("ERRORS:")
        for e in errors[:5]:
            print(e)

    assert marks > 0, "expected in-doc diff highlights"
    assert events >= 3, "expected SSE event log entries"
    assert len(narrative) > 20, "expected streamed narrative"
    assert len(errors) == 0, f"console errors: {errors}"

    browser.close()

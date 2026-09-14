"""Parse ACCEPTANCE_AND_TEST_CASES.md into a formatted Excel workbook.

Columns: Part, Area, TC ID, Title, Priority, Executed, Status, Run, Test (Steps/Expected), Result / Notes.
Adds a Summary sheet with counts. Status cells are colour-coded.
"""
import re
import sys
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter

SRC = Path("ACCEPTANCE_AND_TEST_CASES.md")
OUT = Path("ACCEPTANCE_TEST_CASES.xlsx")

tc_re = re.compile(r"^> \*\*(TC-[A-Z0-9]+-\d+)\s*—\s*(.+?)\*\*(.*)$")
part_re = re.compile(r"^#\s+PART\s+(\S+)")
area_re = re.compile(r"^##\s+([A-K])\.\s+(.+?)\s*$")
detail_re = re.compile(r"^>\s*\*(Pre|Steps|Expected|Result|Note):\*\s*(.*)$")


def strip_md(s: str) -> str:
    s = re.sub(r"`([^`]*)`", r"\1", s)
    s = s.replace("**", "").replace("*", "")
    s = s.replace("⚠", "").replace("🔒", "").replace("🟡", "").replace("✅", "").replace("⏸", "")
    return re.sub(r"\s+", " ", s).strip()


def classify(trailing: str):
    """Return (status, run) from the trailing part of the TC header line."""
    t = trailing
    run = ""
    m = re.search(r"\((Run[^)]*)\)", t)
    if m:
        run = m.group(1).strip()
    up = t.upper()
    if "PARTIAL" in up:
        return "Partial", run
    if "BLOCKED" in up:
        return "Blocked", run
    if "FAIL" in up:
        return "Fail", run
    if "PASS" in up:
        return "Pass", run
    return "Not Run", run


def main():
    if not SRC.exists():
        sys.exit(f"missing {SRC.resolve()}")
    lines = SRC.read_text(encoding="utf-8").splitlines()

    rows = []
    part = ""
    area = "-"
    cur = None
    for ln in lines:
        pm = part_re.match(ln)
        if pm:
            part = pm.group(1)
            area = "-"
            continue
        am = area_re.match(ln)
        if am:
            area = f"{am.group(1)}. {am.group(2)}"
            continue
        tm = tc_re.match(ln)
        if tm:
            tcid, title, trailing = tm.group(1), strip_md(tm.group(2)), tm.group(3)
            pr = re.search(r"P(\d)", trailing)
            priority = f"P{pr.group(1)}" if pr else ""
            status, run = classify(trailing)
            executed = {"Pass": "Yes", "Partial": "Yes", "Fail": "Yes",
                        "Blocked": "Attempted", "Not Run": "No"}[status]
            never = "⚠" in ln and status == "Not Run"
            cur = {
                "Part": part, "Area": area, "TC ID": tcid, "Title": title,
                "Priority": priority, "Executed": executed, "Status": status,
                "Run": run, "Test": "", "Result": "Never device-tested" if never else "",
            }
            rows.append(cur)
            continue
        dm = detail_re.match(ln)
        if dm and cur is not None:
            kind, txt = dm.group(1), strip_md(dm.group(2))
            if kind in ("Result", "Note"):
                cur["Result"] = (cur["Result"] + " " + txt).strip() if cur["Result"] not in ("", "Never device-tested") else txt
            else:  # Pre / Steps / Expected
                seg = f"{kind}: {txt}"
                cur["Test"] = (cur["Test"] + "  " + seg).strip()
            continue
        # any non-quote, non-empty line ends the current TC block
        if cur is not None and not ln.startswith(">"):
            cur = None

    # ---- workbook ----
    wb = Workbook()
    ws = wb.active
    ws.title = "Test Cases"
    headers = ["Part", "Area", "TC ID", "Title", "Priority", "Executed", "Status", "Run", "Test (Steps / Expected)", "Result / Notes"]
    ws.append(headers)

    hdr_fill = PatternFill("solid", fgColor="1F3864")
    hdr_font = Font(bold=True, color="FFFFFF")
    thin = Side(style="thin", color="D9D9D9")
    border = Border(left=thin, right=thin, top=thin, bottom=thin)
    for c, _ in enumerate(headers, 1):
        cell = ws.cell(row=1, column=c)
        cell.fill = hdr_fill
        cell.font = hdr_font
        cell.alignment = Alignment(vertical="center", horizontal="center")
        cell.border = border

    status_fill = {
        "Pass": "C6EFCE", "Partial": "FFEB9C", "Fail": "FFC7CE",
        "Blocked": "FCD5B4", "Not Run": "F2F2F2",
    }
    status_font = {
        "Pass": "006100", "Partial": "9C6500", "Fail": "9C0006",
        "Blocked": "974706", "Not Run": "808080",
    }
    prio_font = {"P0": "9C0006", "P1": "9C6500", "P2": "808080"}

    for r in rows:
        ws.append([r["Part"], r["Area"], r["TC ID"], r["Title"], r["Priority"],
                   r["Executed"], r["Status"], r["Run"], r["Test"], r["Result"]])
        ri = ws.max_row
        for c in range(1, len(headers) + 1):
            cell = ws.cell(row=ri, column=c)
            cell.border = border
            cell.alignment = Alignment(vertical="top", wrap_text=(c in (4, 9, 10)))
        st = ws.cell(row=ri, column=7)
        st.fill = PatternFill("solid", fgColor=status_fill[r["Status"]])
        st.font = Font(bold=True, color=status_font[r["Status"]])
        st.alignment = Alignment(vertical="center", horizontal="center")
        pc = ws.cell(row=ri, column=5)
        if r["Priority"] in prio_font:
            pc.font = Font(bold=True, color=prio_font[r["Priority"]])
        pc.alignment = Alignment(vertical="center", horizontal="center")
        ws.cell(row=ri, column=3).font = Font(bold=True)
        ws.cell(row=ri, column=6).alignment = Alignment(vertical="center", horizontal="center")

    widths = [7, 26, 12, 34, 9, 11, 10, 14, 60, 50]
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    ws.freeze_panes = "A2"
    ws.auto_filter.ref = f"A1:{get_column_letter(len(headers))}{ws.max_row}"

    # ---- summary sheet ----
    sm = wb.create_sheet("Summary", 0)
    total = len(rows)
    by_status = {}
    by_prio_status = {}
    for r in rows:
        by_status[r["Status"]] = by_status.get(r["Status"], 0) + 1
        key = (r["Priority"] or "—", r["Status"])
        by_prio_status[key] = by_prio_status.get(key, 0) + 1

    sm.append(["ACCEPTANCE TEST CASES — STATUS SUMMARY"])
    sm["A1"].font = Font(bold=True, size=14)
    sm.append([f"Source: {SRC.name}", "", f"Total cases: {total}"])
    sm.append([])
    sm.append(["Status", "Count", "% of total"])
    order = ["Pass", "Partial", "Fail", "Blocked", "Not Run"]
    for s in order:
        n = by_status.get(s, 0)
        sm.append([s, n, f"{100*n/total:.0f}%" if total else "0%"])
        cell = sm.cell(row=sm.max_row, column=1)
        cell.fill = PatternFill("solid", fgColor=status_fill[s])
        cell.font = Font(bold=True, color=status_font[s])
    sm.append([])
    sm.append(["Priority × Status", "P0", "P1", "P2"])
    for s in order:
        sm.append([s,
                   by_prio_status.get(("P0", s), 0),
                   by_prio_status.get(("P1", s), 0),
                   by_prio_status.get(("P2", s), 0)])
    sm.append([])
    sm.append(["Executed (Yes)", sum(1 for r in rows if r["Executed"] == "Yes")])
    sm.append(["Attempted/Blocked", sum(1 for r in rows if r["Executed"] == "Attempted")])
    sm.append(["Not executed", sum(1 for r in rows if r["Executed"] == "No")])
    for col, w in zip("ABCD", [22, 10, 10, 10]):
        sm.column_dimensions[col].width = w
    for c in range(1, 5):
        sm.cell(row=4, column=c).font = Font(bold=True)
        sm.cell(row=8, column=c).font = Font(bold=True)

    wb.save(OUT)
    print(f"Wrote {OUT.resolve()}  ({total} test cases)")
    print("Status counts:", {s: by_status.get(s, 0) for s in order})


if __name__ == "__main__":
    main()

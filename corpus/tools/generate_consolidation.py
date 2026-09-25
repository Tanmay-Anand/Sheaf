"""Generates the M12 consolidation fixtures: four source workbooks and one target template.

    python corpus/tools/generate_consolidation.py

Writes corpus/consolidate/booking/*.xlsx, expected.json and README.md.

The layout mirrors a real real-estate CRM export (a bookings report, a loan-customer file, a
payment-receipt register and an address file) feeding a bulk-booking import template. Every value
is invented: a fictional project, fictional people, random phone numbers, PANs and addresses. The
traps are deliberate and listed in expected.json, so tests can check that each one is caught.
"""
import datetime as dt
import io
import json
import os
import random

import openpyxl
from openpyxl.worksheet.datavalidation import DataValidation

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "corpus", "consolidate", "booking")
rng = random.Random(20260925)

PROJECT = "Green Meadows"
N = 30
REGS = [4101 + i for i in range(N)]

FIRST = ["Aarav", "Diya", "Kabir", "Meera", "Rohan", "Isha", "Vivaan", "Anaya", "Arjun", "Saanvi", "Reyansh", "Kiara",
         "Aditya", "Myra", "Vihaan", "Tara", "Krishna", "Riya", "Ishaan", "Nisha", "Dev", "Pooja", "Yash", "Sneha",
         "Om", "Kavya", "Neel", "Aditi", "Rudra", "Zoya"]
LAST = ["Sharma", "Verma", "Gupta", "Mehta", "Iyer", "Nair", "Rao", "Joshi", "Kulkarni", "Chawla", "Bansal", "Saxena"]
TITLES = ["Mr.", "Ms.", "Mrs.", "Dr."]
CITIES = [("Jaipur", "Rajasthan", "302"), ("Indore", "Madhya Pradesh", "452"), ("Pune", "Maharashtra", "411"),
          ("Lucknow", "Uttar Pradesh", "226"), ("Ahmedabad", "Gujarat", "380"), ("Bhopal", "Madhya Pradesh", "462")]
UNIT_TYPES = [("2 BHK (1100.00 Sq. Ft.)", 1100.0, 820.0, 980.0), ("1 RK (500.00 Sq. Ft.)", 500.0, 360.0, 440.0),
              ("3 BHK (1450.00 Sq. Ft.)", 1450.0, 1080.0, 1290.0)]
BANKS = ["SBI HOME LOANS 2", "HDFC BANK", "ICICI BANK LIMITED", "Axis Bank", "state bank of india"]


def phone():
    return int(rng.choice("9876") + "".join(rng.choice("0123456789") for _ in range(9)))


def pan():
    letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    return "".join(rng.choice(letters) for _ in range(5)) + "".join(rng.choice("0123456789") for _ in range(4)) + rng.choice(letters)


people = []
for i, reg in enumerate(REGS):
    fn, ln = FIRST[i], rng.choice(LAST)
    title = rng.choice(TITLES)
    city, state, pin3 = rng.choice(CITIES)
    ut = rng.choice(UNIT_TYPES) if i % 4 else UNIT_TYPES[1]
    allotted = i % 3 != 0  # every third booking has no unit allotted yet: "N/A" texts
    tower = rng.choice("ABC") if allotted else None
    floor_no = rng.randint(1, 18)
    loan = "Loan Sanctioned" if i in (2, 5, 8, 11, 14, 17, 20, 23) else ("Self" if i == 29 else "Pending")
    people.append({
        "reg": reg,
        "sno": i + 1,
        "name": f"{title} {fn} {ln}",
        "booking": dt.datetime(2026, 7, 1) + dt.timedelta(days=rng.randint(0, 40)),
        "mobile": phone(),
        "email": f"{fn.lower()}.{ln.lower()}{rng.randint(1, 99)}@example.com",
        "allotment": (dt.datetime(2026, 8, 1) + dt.timedelta(days=rng.randint(0, 30))) if allotted else "N/A",
        "unit_type": ut,
        "tower": tower,
        "floor": f"{floor_no}{'st' if floor_no == 1 else 'nd' if floor_no == 2 else 'rd' if floor_no == 3 else 'th'} Floor" if allotted else None,
        "unit": int(f"{floor_no}{rng.randint(1, 8):02d}") if allotted else "N/A",
        "loan": loan,
        "bank": rng.choice(BANKS) if loan == "Loan Sanctioned" else "N/A",
        "rate": rng.choice([4200.0, 4350.0, 4500.0]),
        "city": city, "state": state, "pin": f"{pin3}{rng.randint(0, 999):03d}",
        "street": f"{rng.randint(1, 250)}, {rng.choice(['Shanti Nagar', 'Civil Lines', 'Model Town', 'Green Park', 'Sector 9'])}",
        "pan": pan(),
    })

# Traps (by registration number), recorded in expected.json.
TWO_PHONES = {people[4]["reg"], people[13]["reg"]}          # "98xxxxxxxx/ 97xxxxxxxx" in one cell
people[4]["mobile_text"] = f"{people[4]['mobile']}/ {phone()}"
people[13]["mobile_text"] = f"{people[13]['mobile']}, {phone()}"
LOAN_ROWS = [p for p in people if p["loan"] == "Loan Sanctioned"]
CONFLICT_MOBILE = LOAN_ROWS[1]["reg"]                          # loan file has a different mobile
CONFLICT_DATE = LOAN_ROWS[3]["reg"]                            # loan file has a different booking date
NAME_VARIANT = LOAN_ROWS[5]["reg"]                             # same person, name without title: not a conflict after normalising
MISSING_ADDRESS = people[21]["reg"]                            # no row in the address file
NO_PINCODE = people[9]["reg"]                                  # address line without a pincode
PAN_WITH_SPACES = people[16]["reg"]                            # "ABCDE 1234 F"
ORPHAN_RECEIPT_REG = 4999                                      # a receipt for a registration nobody booked
ADDRESS_NAME_CONFLICT = people[25]["reg"]                      # address file spells the name differently (a real conflict)


def save(wb, name):
    os.makedirs(OUT, exist_ok=True)
    wb.save(os.path.join(OUT, name))


# ── 1. Bookings report ("Payment Plan Detail Report"): one row per booking, repeated column blocks ──
data_headers = ["S.No.", "Registration No.", "Name", "Booking Date", "Mobile No.", "Email", "Allotment Date", "Unit Type", "Tower",
                "Floor", "Unit No.", "Plan", "Customer Classification", "Unit Classification", "Main Broker", "Broker", "Team",
                "Employee", "Form No.", "Loan", "Bank", "Area (Sq.Ft.)", "Carpet Area (Sq.Ft.)", "Built Up Area (Sq.Ft.)", "Rate",
                "Rate After Discount", "Average Rate", "Basic Price", "Discount", "Net Basic price", "PLC", "Other Charge", "IFMS",
                "Increase Area Amount", "Total Unit Cost", "Unit Cost Less IFMS", "S.TAX", "CGST", "SGST", "ITC", "TOTAL"]
# Five instalment blocks, each "AMOUNT, S.Tax, CGST, SGST, ITC, TOTAL" (headers repeat, as in the real export).
for block in range(5):
    data_headers += (["(%)"] if block == 2 else []) + ["AMOUNT", "S.Tax", "CGST", "SGST", "ITC", "TOTAL"]
data_headers += ["Receivable", "INTEREST", "RECEIVED", "OUTSTANDING", "RECEIVED GST"]

wb = openpyxl.Workbook()
ws = wb.active
ws.title = "Payment Plan Detail Report"
ws.append(data_headers)
for p in people:
    ut, area, carpet, built = p["unit_type"]
    basic = int(area * p["rate"])
    gst = int(basic * 0.025)
    row = [p["sno"], p["reg"], p["name"], p["booking"], p.get("mobile_text", p["mobile"]), p["email"], p["allotment"], ut, p["tower"],
           p["floor"], p["unit"], "Payment Plan", "N/A", "N/A", None, "Direct", None, None, None, p["loan"], p["bank"], area, carpet,
           built, p["rate"], p["rate"], p["rate"], basic, 0, basic, 0, 0, 0, 0, basic, basic, 0, gst, gst, 0, basic + 2 * gst]
    for block in range(5):
        amt = int(basic * [0.10, 0.25, 0.25, 0.20, 0.20][block])
        g = int(amt * 0.025)
        row += ([rng.choice([10, 25, 25.5])] if block == 2 else []) + [amt, 0, g, g, 0, amt + 2 * g]
    row += [basic + 2 * gst, rng.randint(0, 9000), 0, rng.randint(0, 9000), 0]
    ws.append(row)
for c in ws["D"][1:]:
    c.number_format = "d-mmm-yy"
save(wb, "project-bookings.xlsx")

# ── 2. Loan customers ("Finance_Report"): a subset, with a totals row at the end ──
loan_headers = ["S. No.", "Registration No.", "Name", "Booking Date", "Mobile No", "Email Id", "Unit Type", "Tower", "Floor", "Unit No.",
                "Customer Type", "Plan", "Area", "Total Cost", "Type", "Employee Name", "File No.", "File Date", "Bank Name", "Branch Name",
                "Bank Email ID", "Account No", "Contact Person", "Contact No.", "PTM Date", "TPT Date", "Sanctioned Date",
                "Sanctioned Amount", "Disbursed Amount", "Balance Amount", "Remarks"]
wb = openpyxl.Workbook()
ws = wb.active
ws.title = "Finance_Report"
ws.append(loan_headers)
total_cost = sanctioned_total = disbursed_total = balance_total = 0
for i, p in enumerate(LOAN_ROWS):
    ut, area, _, _ = p["unit_type"]
    cost = int(area * p["rate"])
    sanctioned = int(cost * 0.8)
    disbursed = int(sanctioned * rng.choice([0, 0, 0.25, 0.5]))
    name = p["name"].split(" ", 1)[1] if p["reg"] == NAME_VARIANT else p["name"]           # title dropped
    booking = p["booking"] + dt.timedelta(days=3) if p["reg"] == CONFLICT_DATE else p["booking"]
    mobile = phone() if p["reg"] == CONFLICT_MOBILE else p["mobile"]
    file_date = p["booking"] + dt.timedelta(days=rng.randint(5, 20))
    ws.append([i + 1, p["reg"], name, booking, mobile, p["email"], ut, p["tower"], p["floor"], p["unit"], "Loan", "Payment Plan", area,
               cost, "Loan", "Ms. Priya Sethi", "N/A" if i % 2 else f"GM/{2026}/{100 + i}", file_date, p["bank"], "N/A",
               None, int("5" + "".join(rng.choice("0123456789") for _ in range(10))), "N/A", "N/A", file_date + dt.timedelta(days=2),
               file_date + dt.timedelta(days=4), file_date + dt.timedelta(days=6), sanctioned, disbursed, sanctioned - disbursed, "N/A"])
    total_cost += cost
    sanctioned_total += sanctioned
    disbursed_total += disbursed
    balance_total += sanctioned - disbursed
ws.append([None] * 12 + ["Total:", total_cost] + [None] * 13 + [sanctioned_total, disbursed_total, balance_total, None])
for col in ("D", "R", "Y", "Z", "AA"):
    for c in ws[col][1:]:
        c.number_format = "mm-dd-yy" if col != "R" else "d-mmm-yy"
save(wb, "loan-customers.xlsx")

# ── 3. Payment receipts ("ReceiptWiseCollection"): several per booking, cancelled/refund rows, summary rows ──
rec_headers = ["S.No.", "Registration No.", "Name", "Registration Date", "Unit No.", "Team", "Customer Classification", "Received from",
               "Receipt Date", "Receipt No.", "Amount", "CGST", "SGST", "Extra Addon Amt.", "Net Amount", "Type", "Cheque No.",
               "Bank Name", "Branch Name", "Cheque Date", "Status", "Clear/Bounce Date", "Clear Bank Name", "Deposited Date",
               "Deposited Bank Name", "On Account Of", "Remark", "Receipt Status", "Cancel remark", "Created By", "Created Date"]
wb = openpyxl.Workbook()
ws = wb.active
ws.title = "ReceiptWiseCollection"
ws.append(rec_headers)
receipts = []
sno = 0
for p in people:
    count = rng.choice([1, 1, 2, 3, 4])
    for k in range(count):
        sno += 1
        date = p["booking"] + dt.timedelta(days=0 if k == 0 else rng.randint(10, 60) * k)
        amount = 51000 if k == 0 else rng.choice([100000, 150000, 250000])
        typ = "Cheque" if (sno % 7 == 0) else "Online"
        status = "Clear" if k == 0 or rng.random() < 0.7 else "Pending"
        cancel = p["reg"] == people[7]["reg"] and k == 1
        refund = p["reg"] == people[18]["reg"] and k == count - 1 and count > 1
        receipts.append({"reg": p["reg"], "date": date, "amount": -amount if refund else amount, "type": "Refund" if refund else typ,
                         "status": status, "cancel": cancel, "no": f"R-{7000 + sno}"})
        g = int(amount * 0.025)
        ws.append([sno, p["reg"], p["name"], p["booking"], p["unit"], "N/A", "N/A", "Loan" if (p["loan"] == "Loan Sanctioned" and k > 0) else "Personal",
                   date, f"R-{7000 + sno}", -amount if refund else amount, g, g, 0, (-amount if refund else amount) + 2 * g,
                   "Refund" if refund else typ, f"UTR-HDFC{rng.randint(10**9, 10**10 - 1)}" if typ == "Online" else f"{rng.randint(100000, 999999)}",
                   rng.choice(["HDFC", "SBI", "-"]), "-", date, status, date + dt.timedelta(days=2) if status == "Clear" else None,
                   "SAMPLE BANK" if status == "Clear" else None, "N/A", None, None, None, "Cancel" if cancel else None,
                   "duplicate entry" if cancel else None, "system", date + dt.timedelta(hours=11)])
# A receipt for a registration that isn't in the bookings report (an orphan).
sno += 1
ws.append([sno, ORPHAN_RECEIPT_REG, "Mr. Unknown Buyer", dt.datetime(2026, 7, 20), "N/A", "N/A", "N/A", "Personal", dt.datetime(2026, 7, 20),
           f"R-{7000 + sno}", 51000, 1275, 1275, 0, 53550, "Online", "UTR-HDFC0000000001", "HDFC", "-", dt.datetime(2026, 7, 20), "Clear",
           dt.datetime(2026, 7, 22), "SAMPLE BANK", "N/A", None, None, None, None, None, "system", dt.datetime(2026, 7, 20, 11)])
# Summary rows at the bottom, labels in "Receipt Date", some merged across the first columns (as the real export does).
last = ws.max_row
cleared = sum(r["amount"] for r in receipts if r["status"] == "Clear" and not r["cancel"])
for j, (label, value) in enumerate([("Total Clear Amount :", cleared), ("Total Pending Amount :", sum(r["amount"] for r in receipts if r["status"] == "Pending")),
                                    ("Total Cancelled Amount :", sum(r["amount"] for r in receipts if r["cancel"]))]):
    r = last + 2 + j
    ws.cell(row=r, column=9, value=label)
    ws.cell(row=r, column=11, value=value)
    ws.merge_cells(start_row=r, start_column=1, end_row=r, end_column=8)
for col in ("D", "I", "T", "V"):
    for c in ws[col][1:]:
        if isinstance(c.value, dt.datetime):
            c.number_format = "d-mmm-yy"
save(wb, "payment-receipts.xlsx")

# ── 4. Addresses: one line per applicant, pincode (usually) at the end ──
wb = openpyxl.Workbook()
ws = wb.active
ws.title = "Sheet0"
ws.append(["Sr. No.", "Registration No.", "Name", "Mobile No.", "First Applicant Address", "Pan No."])
k = 0
for p in people:
    if p["reg"] == MISSING_ADDRESS:
        continue
    k += 1
    if p["reg"] == NO_PINCODE:
        address = f"{p['street']}, {p['city']}, {p['state']}"
    else:
        address = rng.choice([f"{p['street']}, {p['city']}, {p['state']}, {p['pin']}",
                              f"{p['street']},{p['city']}-{p['pin']}, {p['state']}",
                              f"{p['street']}, Near City Mall, {p['city']}, {p['state']} - {p['pin']}"])
    name = "Mrs. " + p["name"].split(" ", 1)[1] + " Kapoor" if p["reg"] == ADDRESS_NAME_CONFLICT else p["name"]
    pan_no = f"{p['pan'][:5]} {p['pan'][5:9]} {p['pan'][9]}" if p["reg"] == PAN_WITH_SPACES else p["pan"]
    ws.append([k, p["reg"], name, p.get("mobile_text", p["mobile"]), address, pan_no])
save(wb, "addresses.xlsx")

# ── 5. The target: a bulk-booking import template (header row + dropdowns on rows 2–501) ──
template_headers = ["S. No", "Project Name", "Unit Number", "Phase", "Block", "Buyer Phone", "Buyer Country Code", "Buyer Name", "Buyer Email",
                    "Buyer Type", "Buyer Address Line 1", "Buyer Address Line 2", "Buyer City", "Buyer State", "Buyer Country",
                    "Buyer Pincode", "Pan Card", "Aadhaar Card", "Booking Date", "Status", "Rate Per Sq Ft", "Payment Plan",
                    "Financing Mode", "Booking Amount", "Slot Amount", "Slot Total Amount", "Slot GST Rate", "Booking Percentage",
                    "Builder Discount %", "Builder Discount Amount", "GST Rate", "Stamp Duty Rate", "Stamp Duty State", "TDS Rate",
                    "Token Amount", "Token Date", "Payment Mode", "Payment Ref", "Cancellation Charge %", "Channel Partner Phone",
                    "Channel Partner Country Code", "Primary Buyer Is Female", "Cheque Clearance Days", "Agreement For Sale Deadline",
                    "External ID"]
wb = openpyxl.Workbook()
ws = wb.active
ws.title = "Bookings"
ws.append(template_headers)
lists = {"T": "DRAFT,HOLD,PENDING,CONFIRMED,ACTIVE,CANCELLED,COMPLETED,ALLOTMENT_PENDING",
         "J": "RESIDENT_INDIVIDUAL,NRI,OCI_PIO,HUF,COMPANY",
         "W": "SELF_FUNDED,BANK_LOAN,COMBINATION",
         "AK": "CASH,CHEQUE,BANK_TRANSFER,UPI,CARD",
         "AP": "TRUE,FALSE"}
for col, values in lists.items():
    dv = DataValidation(type="list", formula1=f'"{values}"', allow_blank=True)
    dv.add(f"{col}2:{col}501")
    ws.add_data_validation(dv)
save(wb, "bulk-booking-template.xlsx")

# ── Expected facts, for tests ──
expected = {
    "bookings": N,
    "key": "Registration No.",
    "rowsPerBooking": {"project-bookings.xlsx": 1, "loan-customers.xlsx": "0..1", "addresses.xlsx": "0..1", "payment-receipts.xlsx": "0..n"},
    "notRows": {
        "loan-customers.xlsx": "the last row is a totals row (Area = 'Total:')",
        "payment-receipts.xlsx": "three summary rows at the bottom ('Total … Amount :' in Receipt Date, merged cells)",
    },
    "conflicts": [
        {"registration": CONFLICT_MOBILE, "column": "mobile", "sources": ["project-bookings.xlsx", "loan-customers.xlsx"]},
        {"registration": CONFLICT_DATE, "column": "booking date", "sources": ["project-bookings.xlsx", "loan-customers.xlsx"]},
        {"registration": ADDRESS_NAME_CONFLICT, "column": "name", "sources": ["project-bookings.xlsx", "addresses.xlsx"]},
    ],
    "notConflicts": [
        {"registration": NAME_VARIANT, "why": "same name without the title (Mr./Ms.): equal after normalising"},
        {"registration": sorted(TWO_PHONES), "why": "two numbers in one cell: the first matches; the second is kept as extra"},
    ],
    "unmatched": {
        "missingAddress": [MISSING_ADDRESS],
        "orphanReceipts": [ORPHAN_RECEIPT_REG],
    },
    "dataQuality": {
        "noPincode": [NO_PINCODE],
        "panWithSpaces": [PAN_WITH_SPACES],
        "notYetAllotted": [p["reg"] for p in people if p["unit"] == "N/A"],
        "cancelledReceipts": [r["reg"] for r in receipts if r["cancel"]],
        "refundReceipts": [r["reg"] for r in receipts if r["type"] == "Refund"],
    },
    "reconciliation": {
        "clearedReceiptTotal": cleared,
        "receiptsPerBooking": {str(p["reg"]): sum(1 for r in receipts if r["reg"] == p["reg"]) for p in people},
    },
    "templateCapacity": {"validatedRows": 500, "note": "dropdowns cover rows 2–501; more bookings than that must be flagged"},
}
with io.open(os.path.join(OUT, "expected.json"), "w", encoding="utf-8", newline="\n") as f:
    f.write(json.dumps(expected, indent=2, default=str) + "\n")
print(f"wrote {OUT}: 4 sources + template, {N} bookings, {len(receipts) + 1} receipts")

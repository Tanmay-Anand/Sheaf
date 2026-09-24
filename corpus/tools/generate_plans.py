"""Generates the type checker's corpus fixtures.

    python corpus/tools/generate_plans.py

Writes:
  corpus/plans/*.json                              golden plans: an unbound plan (what a model writes)
                                                   plus what binding and checking must produce
  service/src/test/resources/invalid-plans.json    plans that must fail, each with its expected code

Plans are in the v1.2 wire format the contract schema describes (contract/schema/unbound-plan.schema.json):
tagged with kind / op / type / mode / at, column references and literals always explicit, and dates
marked with valueType. Edit this file and re-run it rather than editing the JSON by hand.
"""
import io
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# ── builders ────────────────────────────────────────────────────────────────
def col(n): return {"type": "col", "col": n}
def lit(v, value_type=None):
    d = {"type": "lit", "value": v}
    if value_type: d["valueType"] = value_type
    return d
def date(v): return lit(v, "date")
def param(n): return {"type": "param", "name": n}
def pred(op, l, r): return {"op": op, "left": l, "right": r}
def eq(c, v): return pred("eq", col(c), v if isinstance(v, dict) else lit(v))
def ne(c, v): return pred("ne", col(c), v if isinstance(v, dict) else lit(v))
def lt(c, v): return pred("lt", col(c), v if isinstance(v, dict) else lit(v))
def gte(c, v): return pred("gte", col(c), v if isinstance(v, dict) else lit(v))
def in_(c, vals): return {"op": "in", "col": c, "values": vals}
def and_(*cl): return {"op": "and", "clauses": list(cl)}
def or_(*cl): return {"op": "or", "clauses": list(cl)}
def not_(p): return {"op": "not", "clause": p}
def isnull(c): return {"op": "isNull", "col": c}

def filter_(p): return {"op": "filter", "predicate": p}
def derive(as_, e): return {"op": "derive", "as": as_, "expr": e}
def m(fn, of, as_, where=None):
    d = {"fn": fn, "of": of, "as": as_}
    if where is not None: d["where"] = where
    return d
def agg(group, *measures): return {"op": "aggregate", "groupBy": group, "measures": list(measures)}
def sort(*keys): return {"op": "sort", "by": [{"col": c, "dir": d} for c, d in keys]}
def limit(n): return {"op": "limit", "n": n}
def join(with_, left, right, kind="inner"): return {"op": "join", "with": with_, "on": {"left": left, "right": right}, "kind": kind}
def lookup(with_, left, right, take=None):
    d = {"op": "lookup", "with": with_, "on": {"left": left, "right": right}}
    if take is not None: d["take"] = take
    return d
def pivot(rows, cols, values): return {"op": "pivot", "rows": rows, "cols": cols, "values": values}
def period(metric, dim, grain, cur, prior, group=None):
    d = {"op": "periodCompare", "metric": metric, "timeDim": dim, "grain": grain, "current": cur, "prior": prior}
    if group is not None: d["groupBy"] = group
    return d
def project(*cols): return {"op": "project", "columns": [{"as": a, "expr": e} for a, e in cols]}

def monthOf(c): return {"type": "monthOf", "col": c}
def quarterOf(c): return {"type": "quarterOf", "col": c}
def isoWeekOf(c): return {"type": "isoWeekOf", "col": c}
def sub(a, b): return {"type": "sub", "a": a, "b": b}
def pct(n, d): return {"type": "pct", "numerator": n, "denominator": d}
def sumAll(c): return {"type": "sumAll", "col": c}
def concat(parts, sep=None, skip=None):
    d = {"type": "concat", "parts": parts}
    if sep is not None: d["sep"] = sep
    if skip is not None: d["skipNulls"] = skip
    return d
def splitPart(arg, sep, idx): return {"type": "splitPart", "arg": arg, "sep": sep, "index": idx}
def toText(arg, pattern): return {"type": "toText", "arg": arg, "pattern": pattern}
def case(whens, else_): return {"type": "case", "when": [{"if": p, "then": v} for p, v in whens], "else": else_}

# Sink intents: the model never writes a location, only where it would like the result to go.
def newsheet(name="Analysis"): return {"mode": "newSheet", "name": name}
def anchor(): return {"mode": "anchor"}
def template(template_id): return {"mode": "template", "templateId": template_id}

def query(source, steps, sink=None, params=None):
    return {"kind": "query", "source": source, "steps": steps, "sink": sink or newsheet(), "params": params or []}
def edit(target, ops, params=None):
    return {"kind": "edit", "target": target, "ops": ops, "params": params or []}

def first(): return {"at": "first"}
def last(): return {"at": "last"}
def after(c): return {"at": "after", "column": c}
def addColumn(as_, e, position=None): return {"op": "addColumn", "as": as_, "expr": e, "position": position or last()}
def setColumn(c, e, where=None):
    d = {"op": "setColumn", "col": c, "expr": e}
    if where is not None: d["where"] = where
    return d
def dropColumn(c): return {"op": "dropColumn", "col": c}
def renameColumn(c, to): return {"op": "renameColumn", "col": c, "to": to}
def moveColumn(c, position): return {"op": "moveColumn", "col": c, "position": position}
def dropRows(where): return {"op": "dropRows", "where": where}

NOT_RETURNED = ne("status", "returned")
EXACT, SYN, RICH = "crm-exact", "crm-synonyms", "crm-enriched"
# "Analysis" already exists in the corpus workbook, so the binder must pick the next free name.
BOUND_SHEET = {"mode": "newSheet", "name": "Analysis 2", "anchor": "A1"}
CONTACTS = ["contact_id", "first_name", "last_name", "email", "phone", "company", "street", "city", "postcode",
            "region", "signup_date", "status", "fax", "lifetime_value"]
CONTACT_TYPES = {"contact_id": "string", "first_name": "string", "last_name": "string", "email": "string",
                 "phone": "string", "company": "string?", "street": "string?", "city": "categorical",
                 "postcode": "string?", "region": "categorical", "signup_date": "date", "status": "categorical",
                 "fax": "string?", "lifetime_value": "currency:INR"}
def contacts(order):
    return [f"{c}: {CONTACT_TYPES[c]}" for c in order]

TASKS = ["project_id: categorical", "project_name: categorical", "task_id: string", "task: string",
         "assignee: categorical", "start_date: date", "end_date: date", "status: categorical",
         "priority: categorical", "effort_hours: number", "completion_pct: percent"]
INVENTORY = ["item_id: string", "item_name: string", "category: categorical", "warehouse: categorical",
             "qty_on_hand: number", "unit_cost: currency:INR", "reorder_level: number", "last_restocked: date",
             "supplier: string"]

# ── golden plans ────────────────────────────────────────────────────────────
Q1 = query("Orders", [filter_(NOT_RETURNED), agg(["region"], m("sum", "amount", "revenue")), sort(("revenue", "desc"))])
GOLDEN = [
    ("q01", "total revenue by region", Q1,
     {"output": ["region: categorical", "revenue: currency:INR"], "sink": BOUND_SHEET, "entities": ["t:Orders"]}),
    ("q02", "total revenue by region, excluding returned orders", Q1,
     {"output": ["region: categorical", "revenue: currency:INR"]}),
    ("q03", "number of orders by channel",
     query("orders", [agg(["channel"], m("count", "*", "order_count")), sort(("order_count", "desc"))]),
     {"output": ["channel: categorical", "order_count: number"], "entities": ["t:Orders"]}),
    ("q04", "average order value by region and channel",
     query("Orders", [agg(["region", "channel"], m("avg", "amount", "avg_order_value")),
                      sort(("region", "asc"), ("avg_order_value", "desc"))]),
     {"output": ["region: categorical", "channel: categorical", "avg_order_value: currency:INR"]}),
    ("q05", "monthly revenue in 2025",
     query("Orders", [filter_(and_(NOT_RETURNED, gte("order_date", date("2025-01-01")), lt("order_date", date("2026-01-01")))),
                      derive("month", monthOf("order_date")), agg(["month"], m("sum", "amount", "revenue")),
                      sort(("month", "asc"))]),
     {"output": ["month: number", "revenue: currency:INR"]}),
    ("q06", "quarterly revenue by region, as a pivot table",
     query("Orders", [filter_(NOT_RETURNED), derive("quarter", quarterOf("order_date")),
                      agg(["region", "quarter"], m("sum", "amount", "revenue")), pivot(["region"], "quarter", "revenue")]),
     {"output": ["region: categorical", "*: currency:INR? (dynamic)"]}),
    ("q07", "weekly order count, last 8 weeks (asOf is a run-time parameter)",
     query("Orders", [filter_(gte("order_date", param("asOf"))), derive("week", isoWeekOf("order_date")),
                      agg(["week"], m("count", "*", "order_count")), sort(("week", "asc"))],
           params=[{"name": "asOf", "valueType": "date"}]),
     {"output": ["week: number", "order_count: number"]}),
    ("q08", "top 5 products by revenue",
     query("Orders", [filter_(NOT_RETURNED), agg(["product_name"], m("sum", "amount", "revenue")),
                      sort(("revenue", "desc")), limit(5)]),
     {"output": ["product_name: string", "revenue: currency:INR"]}),
    ("q09", "revenue share by region as a percentage of total",
     query("Orders", [filter_(NOT_RETURNED), agg(["region"], m("sum", "amount", "revenue")),
                      derive("revenue_share", pct(col("revenue"), sumAll("revenue"))), sort(("revenue_share", "desc"))]),
     {"output": ["region: categorical", "revenue: currency:INR", "revenue_share: percent"]}),
    ("q10", "return rate by product category",
     query("Orders", [agg(["product_category"], m("count", "*", "total_orders"),
                          m("countIf", "*", "returned_orders", eq("status", "returned"))),
                      derive("return_rate", pct(col("returned_orders"), col("total_orders"))), sort(("return_rate", "desc"))]),
     {"output": ["product_category: categorical", "total_orders: number", "returned_orders: number", "return_rate: percent"]}),
    ("q11", "revenue this quarter vs last quarter by region",
     query("Orders", [filter_(NOT_RETURNED), period("Revenue", "order_date", "quarter", "2025-Q1", "2024-Q4", ["region"])]),
     {"output": ["region: categorical", "current_revenue: currency:INR?", "prior_revenue: currency:INR?",
                 "delta: currency:INR?", "delta_pct: percent?"]}),
    ("q12", "actual vs budgeted spend by category for Jan–Apr 2025",
     query("Budget", [filter_(and_(eq("year", 2025), in_("month", ["Jan", "Feb", "Mar", "Apr"]))),
                      agg(["category"], m("sum", "budgeted_amount", "budgeted"), m("sum", "actual_amount", "actual")),
                      derive("variance", sub(col("actual"), col("budgeted"))),
                      derive("variance_pct", pct(col("variance"), col("budgeted"))), sort(("variance_pct", "desc"))]),
     {"output": ["category: categorical", "budgeted: currency:INR", "actual: currency:INR",
                 "variance: currency:INR", "variance_pct: percent"]}),
    ("q13", "effort hours by assignee, for incomplete tasks",
     query("Tasks", [filter_(not_(eq("status", "completed"))), agg(["assignee"], m("sum", "effort_hours", "remaining_hours")),
                     sort(("remaining_hours", "desc"))]),
     {"output": ["assignee: categorical", "remaining_hours: number"]}),
    ("q14", "overdue tasks by project, sorted by priority",
     query("Tasks", [filter_(or_(eq("status", "overdue"), and_(ne("status", "completed"), lt("end_date", date("2025-03-28"))))),
                     sort(("project_name", "asc"), ("priority", "asc"))]),
     {"output": TASKS}),
    ("q15", "inventory items below reorder level, by warehouse",
     query("Inventory", [filter_(lt("qty_on_hand", col("reorder_level"))), sort(("warehouse", "asc"), ("qty_on_hand", "asc"))]),
     {"output": INVENTORY}),

    # ── edits ──
    ("q21", "merge first and last name into one column",
     edit("Contacts", [addColumn("full_name", concat([col("first_name"), col("last_name")], " "), after("last_name")),
                       dropColumn("first_name"), dropColumn("last_name")]),
     {"output": ["contact_id: string", "full_name: string"] + contacts(CONTACTS[3:]),
      "impact": {"added": ["full_name"], "removed": ["first_name", "last_name"]}, "entities": ["t:Contacts"]}),
    ("q22", "combine street, city and postcode into one address, skipping blanks",
     edit("Contacts", [addColumn("address", concat([col("street"), col("city"), col("postcode")], ", ", True), after("postcode"))]),
     {"output": contacts(CONTACTS[:9]) + ["address: string"] + contacts(CONTACTS[9:]),
      "impact": {"added": ["address"]}}),
    ("q23", "remove the fax column",
     edit("Contacts", [dropColumn("fax")]),
     {"output": contacts([c for c in CONTACTS if c != "fax"]), "impact": {"removed": ["fax"]}}),
    ("q24", "remove the lifetime value column (a summary formula uses it; the user decides at commit)",
     edit("Contacts", [dropColumn("lifetime_value")]),
     {"output": contacts(CONTACTS[:-1]), "warnings": ["W_DEPENDENTS_NEED_CONSENT"],
      "impact": {"removed": ["lifetime_value"], "dependentsNeedingConsent": ["lifetime_value <- Summary!B2 (exclusive, columnRemoved)"]}}),
    ("q25", "rename phone to mobile",
     edit("Contacts", [renameColumn("phone", "mobile")]),
     {"output": contacts(CONTACTS[:4]) + ["mobile: string"] + contacts(CONTACTS[5:]),
      "impact": {"renamed": ["phone -> mobile"]}}),
    ("q26", "move the email column to the front",
     edit("Contacts", [moveColumn("email", first())]),
     {"output": contacts(["email"] + [c for c in CONTACTS if c != "email"]), "impact": {"moved": ["email"]}}),
    ("q27", "fill blank companies with 'Unknown'",
     edit("Contacts", [setColumn("company", lit("Unknown"), isnull("company"))]),
     {"output": contacts(CONTACTS), "impact": {"overwritten": ["company"]}}),
    ("q28", "delete the test accounts (the summary sums the whole column, so it is meant to change)",
     edit("Contacts", [dropRows(eq("status", "test"))]),
     {"output": contacts(CONTACTS), "impact": {"removesRows": True}}),

    # ── template fill ──
    ("q29", "fill the CRM import template (same headers) from Contacts",
     query("Contacts", [project(("first_name", col("first_name")), ("last_name", col("last_name")),
                                ("email", col("email")), ("company", col("company")))], template(EXACT)),
     {"output": ["first_name: string", "last_name: string", "email: string", "company: string?"],
      "sink": {"mode": "template", "templateId": EXACT, "headerRow": 1, "firstDataRow": 2}}),
    ("q30", "fill the CRM template that uses its own column names",
     query("Contacts", [project(("Given Name", col("first_name")), ("Surname", col("last_name")),
                                ("E-mail Address", col("email")), ("Organisation", col("company")),
                                ("Phone Number", col("phone")), ("Joined On", col("signup_date")))], template(SYN)),
     {"output": ["Given Name: string", "Surname: string", "E-mail Address: string", "Organisation: string?",
                 "Phone Number: string", "Joined On: date"]}),
    ("q31", "fill the enriched CRM template: full name, email domain, region name, month joined, mapped status",
     query("Contacts", [filter_(ne("status", "test")),
                        lookup("Regions", "region", "code", ["region_name"]),
                        project(("Contact Name", concat([col("first_name"), col("last_name")], " ")),
                                ("Email Domain", splitPart(col("email"), "@", 2)),
                                ("Region Name", col("region_name")),
                                ("Customer Since", toText(col("signup_date"), "mmm yyyy")),
                                ("Status", case([(eq("status", "active"), lit("Active")),
                                                 (eq("status", "lead"), lit("Prospect")),
                                                 (eq("status", "churned"), lit("Former"))], lit(None))),
                                ("Owner", lit(None)))], template(RICH)),
     {"output": ["Contact Name: string", "Email Domain: string?", "Region Name: string?", "Customer Since: string",
                 "Status: categorical?", "Owner: string?"],
      "warnings": ["W_UNMAPPED_TEMPLATE_COLUMN"], "entities": ["t:Contacts", "t:Regions"]}),
]

# ── invalid plans: each must fail with exactly this first error ─────────────
agg_region = agg(["region"], m("sum", "amount", "revenue"))
INVALID = [
    # binding and columns
    ("unknown source", "E_UNKNOWN_SOURCE", query("Order", [filter_(NOT_RETURNED)])),
    ("typo in column", "E_UNKNOWN_COLUMN", query("Orders", [filter_(ne("stauts", "returned"))])),
    ("column gone after aggregate", "E_COLUMN_NOT_IN_OUTPUT", query("Orders", [agg_region, sort(("amount", "desc"))])),
    ("derive over an existing name, differing only in case", "E_DERIVE_COLUMN_COLLISION", query("Orders", [derive("Amount", col("unit_price"))])),
    ("measure named like group, differing only in case", "E_DUPLICATE_OUTPUT_COLUMN", query("Orders", [agg(["region"], m("count", "*", "Region"))])),
    ("groupBy typo", "E_AGGREGATE_GROUPBY_UNKNOWN", query("Orders", [agg(["chnl"], m("count", "*", "n"))])),
    # types and literals
    ("sum of a category", "E_TYPE_MISMATCH", query("Orders", [agg(["channel"], m("sum", "region", "x"))])),
    ("currency compared with text", "E_TYPE_INCOMPARABLE", query("Orders", [filter_(eq("amount", "high"))])),
    ("a date written as plain text", "E_TYPE_INCOMPARABLE", query("Orders", [filter_(gte("order_date", "2025-01-01"))])),
    ("an impossible date literal", "E_INVALID_ARGUMENT", query("Orders", [filter_(gte("order_date", date("2025-13-01")))])),
    ("mixing currencies", "E_CURRENCY_UNIT_MISMATCH", query("Fx", [derive("total", {"type": "add", "a": col("amount_inr"), "b": col("amount_usd")})])),
    ("less-than on a nominal category", "E_ORDER_COMPARISON_NON_ORDERED", query("Orders", [filter_(lt("region", "North"))])),
    ("empty in-list", "E_INVALID_ARGUMENT", query("Orders", [filter_(in_("region", []))])),
    ("month of a text column", "E_DATE_EXTRACT_NON_DATE", query("Orders", [derive("m", monthOf("region"))])),
    ("bad toText pattern", "E_INVALID_ARGUMENT", query("Orders", [project(("d", concat([toText(col("order_date"), "dddd")])))])),
    ("toText outside a concat", "E_TOTEXT_OUTSIDE_CONCAT", query("Orders", [derive("amount_text", toText(col("amount"), "#,##0"))])),
    ("toText into a new sheet", "E_TOTEXT_OUTSIDE_CONCAT", query("Orders", [project(("when", toText(col("order_date"), "yyyy-mm")))])),
    ("upper of a number", "E_TYPE_MISMATCH", query("Orders", [derive("u", {"type": "upper", "arg": col("amount")})])),
    ("undeclared parameter", "E_UNKNOWN_PARAM", query("Orders", [filter_(gte("order_date", param("asOf")))])),
    # periods
    ("unknown metric", "E_UNKNOWN_METRIC", query("Orders", [period("Profit", "order_date", "quarter", "2025-Q1", "2024-Q4")])),
    ("unknown time dimension", "E_PERIOD_COMPARE_NO_TIME_DIM", query("Orders", [period("Revenue", "ship_date", "quarter", "2025-Q1", "2024-Q4")])),
    ("grain not supported", "E_PERIOD_COMPARE_UNSUPPORTED_GRAIN", query("Tasks", [period("EffortHours", "task_end_date", "year", "2025", "2024")])),
    ("quarter 5", "E_PERIOD_COMPARE_INVALID_PERIOD", query("Orders", [period("Revenue", "order_date", "quarter", "2025-Q5", "2025-Q4")])),
    ("periodCompare after derive", "E_PERIOD_COMPARE_PLACEMENT",
     query("Orders", [derive("m", monthOf("order_date")), period("Revenue", "order_date", "quarter", "2025-Q1", "2024-Q4")])),
    # joins and lookups
    ("join on an undeclared path", "E_UNAPPROVED_JOIN", query("Orders", [join("Regions", "region", "zone")])),
    ("join on a proposed, unapproved path", "E_UNAPPROVED_JOIN", query("Orders", [join("Inventory", "product_category", "category")])),
    ("join to unknown table", "E_JOIN_ENTITY_UNKNOWN", query("Orders", [join("Region", "region", "code")])),
    ("lookup on a key that repeats", "E_LOOKUP_KEY_NOT_UNIQUE", query("Tasks", [lookup("Budget", "assignee", "owner")])),
    ("lookup of a column that isn't there", "E_UNKNOWN_COLUMN", query("Orders", [lookup("Regions", "region", "code", ["region_nme"])])),
    # pivot
    ("pivot text values", "E_PIVOT_VALUE_NOT_NUMERIC", query("Orders", [pivot(["region"], "channel", "status")])),
    ("pivot on money", "E_PIVOT_NON_CATEGORICAL_COLS", query("Orders", [pivot(["region"], "amount", "quantity")])),
    ("pivot on 75 order ids", "E_PIVOT_HIGH_CARDINALITY", query("Orders", [pivot(["region"], "order_id", "amount")])),
    ("step after pivot", "E_PIVOT_NOT_TERMINAL", query("Orders", [pivot(["region"], "channel", "amount"), sort(("region", "asc"))])),
    # derive
    ("sumAll not after aggregate", "E_SUMALL_INVALID_PLACEMENT", query("Orders", [filter_(NOT_RETURNED), derive("s", sumAll("amount"))])),
    ("sumAll of a non-measure", "E_SUMALL_UNKNOWN_COLUMN", query("Orders", [agg_region, derive("s", pct(col("revenue"), sumAll("amount")))])),
    ("bucket labels count", "E_BUCKET_BREAK_COUNT",
     query("Orders", [derive("size", {"type": "bucket", "col": "amount", "breaks": [1000, 5000], "labels": ["small", "large"]})])),
    ("case without else", "E_CASE_NO_ELSE",
     query("Orders", [derive("c", {"type": "case", "when": [{"if": eq("status", "shipped"), "then": lit("S")}]})])),
    # sort / aggregate / limit
    ("sort by an ordinal category with no declared order", "E_SORT_CATEGORICAL_UNORDERED", query("Tasks", [sort(("status", "asc"))])),
    ("countIf without predicate", "E_COUNTIF_NO_PREDICATE", query("Orders", [agg(["region"], m("countIf", "*", "n"))])),
    ("limit zero", "E_LIMIT_NOT_POSITIVE", query("Orders", [sort(("amount", "desc")), limit(0)])),
    # sinks
    ("no steps into an existing sheet", "E_EMPTY_PIPELINE_WRITE", query("Orders", [], anchor())),
    ("unknown template", "E_TEMPLATE_UNKNOWN", query("Contacts", [project(("first_name", col("first_name")))], template("crm"))),
    # parse
    ("unknown operator", "E_PARSE_UNKNOWN_VARIANT", query("Orders", [{"op": "median", "of": "amount"}])),
    ("unknown aggregate function", "E_PARSE_INVALID_VALUE", query("Orders", [agg(["region"], m("median", "amount", "x"))])),
    ("misspelled field", "E_PARSE_UNKNOWN_FIELD", query("Orders", [{"op": "aggregate", "groupby": ["region"], "measures": []}])),
    ("missing measures", "E_PARSE_MISSING_FIELD", query("Orders", [{"op": "aggregate", "groupBy": ["region"]}])),
    ("limit without n (never read as 0)", "E_PARSE_MISSING_FIELD", query("Orders", [sort(("amount", "desc")), {"op": "limit"}])),
    ("a location written by the model", "E_PARSE_UNKNOWN_FIELD", query("Orders", [agg_region], {"mode": "anchor", "range": "Summary!A1:C10"})),
    ("not JSON", "E_PARSE_INVALID_JSON", "Here is the plan: {kind: query}"),
    # edits
    ("edit with no operations", "E_EDIT_EMPTY", edit("Contacts", [])),
    ("edit an unknown table", "E_UNKNOWN_SOURCE", edit("Contact", [dropColumn("fax")])),
    ("drop a column that isn't there", "E_EDIT_UNKNOWN_COLUMN", edit("Contacts", [dropColumn("fax_number")])),
    ("add a column that exists, differing only in case", "E_EDIT_COLUMN_COLLISION", edit("Contacts", [addColumn("Email", lit("x"))])),
    ("rename onto an existing name", "E_RENAME_COLLISION", edit("Contacts", [renameColumn("phone", "EMAIL")])),
    ("drop every column", "E_EDIT_DROPS_EVERY_COLUMN",
     edit("Regions", [dropColumn("code"), dropColumn("region_name"), dropColumn("zone"), dropColumn("manager"), dropColumn("hq_city")])),
    ("write text into money", "E_TYPE_MISMATCH", edit("Contacts", [setColumn("lifetime_value", lit("n/a"))])),
    ("move a column after itself", "E_INVALID_ARGUMENT", edit("Contacts", [moveColumn("email", after("email"))])),
    ("use a column after dropping it", "E_EDIT_UNKNOWN_COLUMN", edit("Contacts", [dropColumn("fax"), renameColumn("fax", "facsimile")])),
    ("add a column with no position", "E_PARSE_MISSING_FIELD", edit("Contacts", [{"op": "addColumn", "as": "x", "expr": lit(1)}])),
    ("a consent written by the model", "E_PARSE_UNKNOWN_FIELD",
     {"kind": "edit", "target": "Contacts", "ops": [dropColumn("fax")], "params": [], "onDependents": "convertToValues"}),
    # templates
    ("template column missing", "E_TEMPLATE_COLUMN_MISSING",
     query("Contacts", [project(("first_name", col("first_name")), ("last_name", col("last_name")), ("email", col("email")))], template(EXACT))),
    ("writes the template's formula column", "E_TEMPLATE_EXTRA_COLUMN",
     query("Contacts", [project(("Row #", lit(1)), ("Contact Name", col("first_name")), ("Email Domain", lit(None)),
                                ("Region Name", lit(None)), ("Customer Since", lit(None)), ("Status", lit(None)),
                                ("Owner", lit(None)))], template(RICH))),
    ("template columns out of order", "E_TEMPLATE_COLUMN_ORDER",
     query("Contacts", [project(("last_name", col("last_name")), ("first_name", col("first_name")),
                                ("email", col("email")), ("company", col("company")))], template(EXACT))),
    ("text into a date column", "E_TEMPLATE_TYPE_INCOMPATIBLE",
     query("Contacts", [project(("Given Name", col("first_name")), ("Surname", col("last_name")),
                                ("E-mail Address", col("email")), ("Organisation", col("company")),
                                ("Phone Number", col("phone")), ("Joined On", col("email")))], template(SYN))),
    ("toText into a date-formatted template column", "E_TOTEXT_OUTSIDE_CONCAT",
     query("Contacts", [project(("Given Name", col("first_name")), ("Surname", col("last_name")),
                                ("E-mail Address", col("email")), ("Organisation", col("company")),
                                ("Phone Number", col("phone")), ("Joined On", toText(col("signup_date"), "yyyy-mm-dd")))], template(SYN))),
    ("status values outside the template's list", "E_TEMPLATE_VALUE_NOT_IN_LIST",
     query("Contacts", [project(("Contact Name", col("first_name")), ("Email Domain", lit(None)), ("Region Name", lit(None)),
                                ("Customer Since", lit(None)), ("Status", col("status")), ("Owner", lit(None)))], template(RICH))),
    ("pivot into a template", "E_TEMPLATE_DYNAMIC_COLUMNS",
     query("Orders", [pivot(["region"], "channel", "amount")], template(EXACT))),
]


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def main():
    plans_dir = os.path.join(ROOT, "corpus", "plans")
    os.makedirs(plans_dir, exist_ok=True)
    for name in os.listdir(plans_dir):
        if name.endswith(".json"):
            os.remove(os.path.join(plans_dir, name))
    for key, question, plan, expect in GOLDEN:
        write(os.path.join(plans_dir, key + ".json"),
              {"question": question, "expect": {"valid": True, "warnings": [], **expect}, "plan": plan})

    write(os.path.join(ROOT, "service", "src", "test", "resources", "invalid-plans.json"),
          [{"name": n, "expect": code, "plan": plan} for n, code, plan in INVALID])
    edits = sum(1 for _, _, p in INVALID
                if isinstance(p, dict) and (p.get("kind") == "edit" or p.get("sink", {}).get("mode") == "template"))
    print(f"{len(GOLDEN)} golden plans, {len(INVALID)} invalid plans ({edits} edit or template)")


if __name__ == "__main__":
    main()

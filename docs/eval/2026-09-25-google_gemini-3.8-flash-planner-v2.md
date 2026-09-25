# Planner accuracy: google/gemini-3.8-flash (planner-v2)

In scope: 23/26 valid plans within 2 repairs (88%). Out of scope: 6/6 clarified or refused. Cost: $0.3025.

| # | Question | Expected | Got | Pass | Calls | Cost | Note |
|---|---|---|---|---|---|---|---|
| q01 | total revenue by region | plan | plan | ✓ | 1 | $0.0073 | columns [region, total_revenue] |
| q02 | total revenue by region, excluding returned orders | plan | plan | ✓ | 1 | $0.0055 | columns [region, total_revenue] |
| q03 | number of orders by channel | plan | plan | ✓ | 1 | $0.0067 | same columns as golden |
| q04 | average order value by region and channel | plan | plan | ✓ | 1 | $0.0076 | same columns as golden |
| q05 | monthly revenue in 2025 | plan | plan | ✓ | 3 | $0.0301 | same columns as golden · repaired: [E_PARSE_INVALID_VALUE /plan/steps/0/predicate/clauses/1, E_PARSE_INVALID_VALUE /plan/steps/0/predicate/clauses/1] |
| q06 | quarterly revenue by region, as a pivot table | plan | plan | ✓ | 1 | $0.0120 | columns [region] |
| q07 | weekly order count, last 8 weeks (asOf is a run-time parameter) | plan | plan | ✓ | 3 | $0.0327 | columns [year, week, order_count] · repaired: [E_PARSE_INVALID_VALUE /plan/steps, E_PARSE_INVALID_VALUE /plan/steps] |
| q08 | top 5 products by revenue | plan | plan | ✓ | 1 | $0.0059 | same columns as golden |
| q09 | revenue share by region as a percentage of total | plan | plan | ✓ | 2 | $0.0177 | same columns as golden · repaired: [E_PARSE_INVALID_VALUE /plan] |
| q10 | return rate by product category | plan | clarify | ✗ | 1 | $0.0119 | How would you like to calculate the return rate? |
| q11 | revenue this quarter vs last quarter by region | plan | clarify | ✗ | 1 | $0.0119 | Which quarters would you like to compare? |
| q12 | actual vs budgeted spend by category for Jan–Apr 2025 | plan | plan | ✓ | 1 | $0.0087 | columns [category, actual_spend, budgeted_spend] |
| q13 | effort hours by assignee, for incomplete tasks | plan | plan | ✓ | 1 | $0.0064 | columns [assignee, total_effort_hours] |
| q14 | overdue tasks by project, sorted by priority | plan | plan | ✓ | 1 | $0.0115 | same columns as golden |
| q15 | inventory items below reorder level, by warehouse | plan | clarify | ✗ | 1 | $0.0119 | Would you like a detailed list of items below reorder level sorted by warehouse, or a summary count of items by warehouse? |
| q21 | merge first and last name into one column | plan | plan | ✓ | 2 | $0.0181 | same columns as golden · repaired: [E_PARSE_INVALID_VALUE /plan/ops] |
| q22 | combine street, city and postcode into one address, skipping blanks | plan | plan | ✓ | 1 | $0.0082 | same columns as golden |
| q23 | remove the fax column | plan | plan | ✓ | 1 | $0.0033 | same columns as golden |
| q24 | remove the lifetime value column (a summary formula uses it; the user decides at commit) | plan | plan | ✓ | 1 | $0.0047 | same columns as golden |
| q25 | rename phone to mobile | plan | plan | ✓ | 1 | $0.0050 | same columns as golden |
| q26 | move the email column to the front | plan | plan | ✓ | 1 | $0.0044 | same columns as golden |
| q27 | fill blank companies with 'Unknown' | plan | plan | ✓ | 1 | $0.0064 | same columns as golden |
| q28 | delete the test accounts (the summary sums the whole column, so it is meant to change) | plan | plan | ✓ | 1 | $0.0047 | same columns as golden |
| q29 | fill the CRM import template (same headers) from Contacts | plan | plan | ✓ | 1 | $0.0056 | same columns as golden |
| q30 | fill the CRM template that uses its own column names | plan | plan | ✓ | 1 | $0.0092 | same columns as golden |
| q31 | fill the enriched CRM template: full name, email domain, region name, month joined, mapped status | plan | plan | ✓ | 2 | $0.0196 | same columns as golden · repaired: [E_PARSE_INVALID_VALUE /plan/steps/1/columns/4/expr/when] |
| q16 | running total of revenue by week | refuse | refuse | ✓ | 1 | $0.0039 | understood: Calculate a cumulative running total of revenue grouped by week from Orders. |
| q17 | what's selling well? | clarify | clarify | ✓ | 1 | $0.0041 | How would you like to measure what's selling well? |
| q18 | show me this quarter's performance vs target | refuse | refuse | ✓ | 1 | $0.0051 | understood: You want to see performance compared against targets for the current quarter, such as sales revenue or task metrics vs target. |
| q19 | which product categories will grow fastest next quarter? | refuse | refuse | ✓ | 1 | $0.0044 | understood: Forecasting which product categories will grow fastest next quarter |
| q20 | can you make the results table look nicer with better formatting? | refuse | refuse | ✓ | 1 | $0.0044 | understood: applying visual styles, cell formatting, colors, or font changes to a table |
| q32 | make the headers bold | refuse | refuse | ✓ | 1 | $0.0037 | understood: apply bold formatting to table or sheet headers |

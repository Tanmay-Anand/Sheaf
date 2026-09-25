# Planner accuracy: anthropic/claude-sonnet-4.6 (planner-v2)

In scope: 22/26 valid plans within 2 repairs (85%). Out of scope: 6/6 clarified or refused. Cost: $0.5010.

| # | Question | Expected | Got | Pass | Calls | Cost | Note |
|---|---|---|---|---|---|---|---|
| q01 | total revenue by region | plan | plan | ✓ | 1 | $0.0135 | same columns as golden |
| q02 | total revenue by region, excluding returned orders | plan | plan | ✓ | 1 | $0.0136 | same columns as golden |
| q03 | number of orders by channel | plan | plan | ✓ | 1 | $0.0129 | same columns as golden |
| q04 | average order value by region and channel | plan | plan | ✓ | 1 | $0.0154 | columns [region, channel, total_amount, order_count, avg_order_value] |
| q05 | monthly revenue in 2025 | plan | plan | ✓ | 1 | $0.0152 | same columns as golden |
| q06 | quarterly revenue by region, as a pivot table | plan | plan | ✓ | 1 | $0.0141 | columns [region] |
| q07 | weekly order count, last 8 weeks (asOf is a run-time parameter) | plan | plan | ✓ | 1 | $0.0166 | same columns as golden |
| q08 | top 5 products by revenue | plan | plan | ✓ | 1 | $0.0138 | same columns as golden |
| q09 | revenue share by region as a percentage of total | plan | plan | ✓ | 1 | $0.0155 | columns [region, revenue, total_revenue, revenue_pct] |
| q10 | return rate by product category | plan | plan | ✓ | 1 | $0.0150 | same columns as golden |
| q11 | revenue this quarter vs last quarter by region | plan | plan | ✓ | 1 | $0.0143 | same columns as golden |
| q12 | actual vs budgeted spend by category for Jan–Apr 2025 | plan | plan | ✓ | 1 | $0.0171 | columns [category, budgeted_amount, actual_amount, variance, variance_pct] |
| q13 | effort hours by assignee, for incomplete tasks | plan | plan | ✓ | 1 | $0.0138 | columns [assignee, effort_hours] |
| q14 | overdue tasks by project, sorted by priority | plan | plan | ✓ | 1 | $0.0143 | columns [project_id, project_name, priority, task_count] |
| q15 | inventory items below reorder level, by warehouse | plan | plan | ✓ | 1 | $0.0138 | same columns as golden |
| q21 | merge first and last name into one column | plan | clarify | ✗ | 1 | $0.0126 | Which table should the names be merged in? |
| q22 | combine street, city and postcode into one address, skipping blanks | plan | plan | ✓ | 1 | $0.0137 | same columns as golden |
| q23 | remove the fax column | plan | plan | ✓ | 1 | $0.0121 | same columns as golden |
| q24 | remove the lifetime value column (a summary formula uses it; the user decides at commit) | plan | plan | ✓ | 1 | $0.0126 | same columns as golden |
| q25 | rename phone to mobile | plan | clarify | ✗ | 1 | $0.0120 | Which table should the column 'phone' be renamed to 'mobile' in? |
| q26 | move the email column to the front | plan | clarify | ✗ | 1 | $0.0122 | Which table should the email column be moved to the front of? |
| q27 | fill blank companies with 'Unknown' | plan | plan | ✓ | 1 | $0.0128 | same columns as golden |
| q28 | delete the test accounts (the summary sums the whole column, so it is meant to change) | plan | plan | ✓ | 1 | $0.0129 | same columns as golden |
| q29 | fill the CRM import template (same headers) from Contacts | plan | plan | ✓ | 1 | $0.0145 | same columns as golden |
| q30 | fill the CRM template that uses its own column names | plan | plan | ✓ | 1 | $0.0173 | same columns as golden |
| q31 | fill the enriched CRM template: full name, email domain, region name, month joined, mapped status | plan | failed | ✗ | 3 | $0.0743 | [E_TEMPLATE_EXTRA_COLUMN /sink/columns] · repaired: [E_TOTEXT_OUTSIDE_CONCAT /steps/3/expr, E_DERIVE_COLUMN_COLLISION /steps/3/as, E_TEMPLATE_EXTRA_COLUMN /sink/columns] |
| q16 | running total of revenue by week | refuse | refuse | ✓ | 1 | $0.0124 | understood: A running total (cumulative sum) of revenue by week across orders. |
| q17 | what's selling well? | clarify | clarify | ✓ | 1 | $0.0123 | How would you like to measure 'selling well'? |
| q18 | show me this quarter's performance vs target | refuse | refuse | ✓ | 1 | $0.0128 | understood: You want to compare this quarter's actuals against targets, likely for revenue, orders, or budget. |
| q19 | which product categories will grow fastest next quarter? | refuse | refuse | ✓ | 1 | $0.0127 | understood: You want a forecast of which product categories will grow fastest next quarter. |
| q20 | can you make the results table look nicer with better formatting? | refuse | refuse | ✓ | 1 | $0.0125 | understood: Apply visual formatting (colors, fonts, borders, number formats, column widths, etc.) to a results table. |
| q32 | make the headers bold | refuse | refuse | ✓ | 1 | $0.0122 | understood: Make the table headers bold (formatting change). |

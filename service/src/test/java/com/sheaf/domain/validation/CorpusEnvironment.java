package com.sheaf.domain.validation;

import com.sheaf.domain.catalog.ColumnDependent;
import com.sheaf.domain.catalog.DependentClass;
import com.sheaf.domain.catalog.DependentKind;
import com.sheaf.domain.ir.Predicate;
import com.sheaf.domain.ir.Step;
import com.sheaf.domain.types.ScalarType;
import com.sheaf.domain.types.ScalarType.CategoricalType;
import com.sheaf.domain.types.ScalarType.CurrencyType;
import com.sheaf.domain.types.ScalarType.Ordering;
import com.sheaf.domain.types.TypeEnvironment;
import com.sheaf.domain.types.TypeEnvironment.ColumnSchema;
import com.sheaf.domain.types.TypeEnvironment.EntitySchema;
import com.sheaf.domain.types.TypeEnvironment.JoinEdge;
import com.sheaf.domain.types.TypeEnvironment.Metric;
import com.sheaf.domain.types.TypeEnvironment.TemplateColumn;
import com.sheaf.domain.types.TypeEnvironment.TemplateSchema;
import com.sheaf.domain.types.TypeEnvironment.TimeDimension;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The corpus workbook as the type checker sees it: the tables in corpus/*.csv with the types and
 * the semantic model assumed in corpus/questions.md (approved joins, metrics, time dimensions,
 * category orders), plus the three imported templates in corpus/templates/.
 */
public final class CorpusEnvironment {

    static final String EXACT = "crm-exact";
    static final String SYNONYMS = "crm-synonyms";
    static final String ENRICHED = "crm-enriched";

    private static final CurrencyType INR = new CurrencyType("INR");

    /** The corpus workbook's sheets, plus its summary sheet and an existing "Analysis" sheet. */
    static final Set<String> SHEETS = Set.of("Orders", "Regions", "Budget", "Tasks", "Inventory", "Contacts", "Fx", "Summary", "Analysis");

    private CorpusEnvironment() {}

    static ColumnSchema c(String name, ScalarType type) {
        return ColumnSchema.of(name, type);
    }

    static ColumnSchema c(String name, ScalarType type, int cardinality) {
        return new ColumnSchema(name, name, type, false, cardinality, false, false, false, false, List.of());
    }

    /** A key: every value distinct, no blanks. Lookups need one on the right-hand side. */
    static ColumnSchema key(String name, ScalarType type, int cardinality) {
        return new ColumnSchema(name, name, type, false, cardinality, false, true, false, false, List.of());
    }

    static ColumnSchema nullable(String name, ScalarType type) {
        return new ColumnSchema(name, name, type, true, null, false, false, false, false, List.of());
    }

    static CategoricalType nominal(String domain, String... members) {
        return new CategoricalType(domain, members.length == 0 ? null : List.of(members), Ordering.none);
    }

    static CategoricalType ordinal(String domain, String... inOrder) {
        return new CategoricalType(domain, List.of(inOrder), Ordering.declared);
    }

    /** Ids follow the catalog's scheme for Excel Tables: "t:" + table id (here, the name). */
    static EntitySchema entity(String name, ColumnSchema... columns) {
        return new EntitySchema("t:" + name, name, "ws-" + name, Arrays.asList(columns));
    }

    public static TypeEnvironment build() {
        Map<String, EntitySchema> e = new LinkedHashMap<>();
        e.put("Orders", entity("Orders",
                key("order_id", ScalarType.STRING, 75),
                c("order_date", ScalarType.DATE),
                c("region", nominal("region", "Central", "East", "NE", "North", "South", "West"), 6),
                c("channel", nominal("channel", "direct", "online", "retail", "wholesale"), 4),
                c("product_category", nominal("product_category", "Apparel", "Electronics", "Food", "Home", "Sports"), 5),
                c("product_name", ScalarType.STRING, 75),
                c("quantity", ScalarType.NUMBER),
                c("unit_price", INR),
                c("amount", INR),
                c("discount_pct", ScalarType.PERCENT),
                c("status", nominal("status", "shipped", "returned", "pending"), 3),
                c("customer_type", nominal("customer_type", "retail", "business"), 2)));
        e.put("Regions", entity("Regions",
                key("code", ScalarType.STRING, 6),
                key("region_name", ScalarType.STRING, 6),
                c("zone", nominal("zone", "Central", "East", "North", "South", "West"), 5),
                c("manager", ScalarType.STRING),
                c("hq_city", ScalarType.STRING)));
        e.put("Budget", entity("Budget",
                c("category", nominal("category", "Engineering", "HR", "Marketing", "Operations"), 4),
                c("subcategory", nominal("subcategory"), 8),
                c("owner", nominal("owner"), 4),
                c("month", ordinal("month", "Jan", "Feb", "Mar", "Apr"), 4),
                c("year", ScalarType.NUMBER),
                c("budgeted_amount", INR),
                c("actual_amount", INR),
                c("currency", nominal("currency", "INR"), 1),
                nullable("notes", ScalarType.STRING)));
        e.put("Tasks", entity("Tasks",
                c("project_id", nominal("project_id"), 5),
                c("project_name", nominal("project_name"), 5),
                key("task_id", ScalarType.STRING, 25),
                c("task", ScalarType.STRING, 25),
                c("assignee", nominal("assignee"), 5),
                c("start_date", ScalarType.DATE),
                c("end_date", ScalarType.DATE),
                // Workflow states have a natural order nobody has declared yet: sorting must be refused.
                c("status", new CategoricalType("status", List.of("completed", "in_progress", "not_started", "overdue"), Ordering.missing), 4),
                c("priority", ordinal("priority", "low", "medium", "high", "critical"), 4),
                c("effort_hours", ScalarType.NUMBER),
                c("completion_pct", ScalarType.PERCENT)));
        e.put("Inventory", entity("Inventory",
                key("item_id", ScalarType.STRING, 24),
                c("item_name", ScalarType.STRING, 24),
                c("category", nominal("category", "Apparel", "Electronics", "Food", "Home", "Sports"), 5),
                c("warehouse", nominal("warehouse", "Bangalore", "Delhi", "Mumbai"), 3),
                c("qty_on_hand", ScalarType.NUMBER),
                c("unit_cost", INR),
                c("reorder_level", ScalarType.NUMBER),
                c("last_restocked", ScalarType.DATE),
                c("supplier", ScalarType.STRING)));
        e.put("Contacts", entity("Contacts",
                key("contact_id", ScalarType.STRING, 16),
                c("first_name", ScalarType.STRING),
                c("last_name", ScalarType.STRING),
                c("email", ScalarType.STRING),
                c("phone", ScalarType.STRING),
                nullable("company", ScalarType.STRING),
                nullable("street", ScalarType.STRING),
                c("city", nominal("city"), 11),
                nullable("postcode", ScalarType.STRING),
                c("region", nominal("region", "Central", "East", "NE", "North", "South", "West"), 6),
                c("signup_date", ScalarType.DATE),
                c("status", nominal("status", "active", "lead", "churned", "test"), 4),
                nullable("fax", ScalarType.STRING),
                // Summary!B2 sums the whole data range of this one column: deleting the column breaks it
                // (#REF!), deleting rows is what it is meant to follow (not fixed rows).
                new ColumnSchema("lifetime_value", "lifetime_value", INR, false, null, false, false, false, false,
                        List.of(new ColumnDependent(DependentKind.formula, "Summary!B2", "=SUM(Contacts!N2:N17)",
                                DependentClass.exclusive, false)))));
        // A two-currency table, only to exercise unit checks.
        e.put("Fx", entity("Fx", c("amount_inr", INR), c("amount_usd", new CurrencyType("USD"))));

        var joins = List.of(
                new JoinEdge("Orders", "region", "Regions", "code", true),
                new JoinEdge("Contacts", "region", "Regions", "code", true),
                new JoinEdge("Orders", "product_category", "Inventory", "category", false),
                // Approved, but Budget.owner repeats: fine for a join, refused for a lookup.
                new JoinEdge("Tasks", "assignee", "Budget", "owner", true));

        var notReturned = new Predicate.NePredicate(new Predicate.ValueRef.ColRef("status"), new Predicate.ValueRef.Lit("returned", null));
        Map<String, Metric> metrics = Map.of(
                "Revenue", new Metric("Revenue", "Orders", Step.AggFn.sum, "amount", List.of(notReturned), INR),
                "OrderCount", new Metric("OrderCount", "Orders", Step.AggFn.count, "*", List.of(), ScalarType.NUMBER),
                "EffortHours", new Metric("EffortHours", "Tasks", Step.AggFn.sum, "effort_hours", List.of(), ScalarType.NUMBER));
        Map<String, TimeDimension> dims = Map.of(
                "order_date", new TimeDimension("order_date", "Orders", "order_date", EnumSet.allOf(Step.Grain.class), "monday"),
                "task_end_date", new TimeDimension("task_end_date", "Tasks", "end_date",
                        Set.of(Step.Grain.day, Step.Grain.week, Step.Grain.month), null));

        Map<String, TemplateSchema> templates = Map.of(
                EXACT, new TemplateSchema(EXACT, "Template – crm-exact", 1, 2, List.of(
                        text("first_name"), text("last_name"), text("email"), text("company"))),
                SYNONYMS, new TemplateSchema(SYNONYMS, "Template – crm-synonyms", 1, 2, List.of(
                        text("Given Name"), text("Surname"), text("E-mail Address"), text("Organisation"), text("Phone Number"),
                        new TemplateColumn("Joined On", ScalarType.DATE, null, false))),
                ENRICHED, new TemplateSchema(ENRICHED, "Template – crm-enriched", 1, 2, List.of(
                        new TemplateColumn("Row #", ScalarType.NUMBER, null, true),
                        text("Contact Name"), text("Email Domain"), text("Region Name"),
                        // Text-formatted ("@") in the template, so toText into it is allowed.
                        new TemplateColumn("Customer Since", ScalarType.STRING, null, false),
                        new TemplateColumn("Status", ScalarType.STRING, List.of("Active", "Prospect", "Former"), false),
                        text("Owner"))));

        return new TypeEnvironment(e, joins, metrics, dims, templates, SHEETS, false);
    }

    private static TemplateColumn text(String name) {
        return new TemplateColumn(name, null, null, false);
    }
}

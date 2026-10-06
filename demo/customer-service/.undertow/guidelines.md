# 1. Payment identity

Use the order's persistent operation key for each attempt. A lost response may follow a successful charge. A retry must identify the same operation, not generate a fresh key. Gateway deduplication is a declared collaborator contract requiring live verification.

# 2. Tenant ownership

Use authenticated tenant context for every data lookup. Caller-supplied order IDs do not authorize access. Distinct tenants may have equal order IDs; cache keys must include tenant identity.

# 3. Evidence and unsupported scope

Application CI executes tests. Review-generated regression tests are proposals. Missing CI and unsupported changes must remain explicit coverage gaps.

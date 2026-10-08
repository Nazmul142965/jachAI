# Startup failure: `sumAmountBdtBy…` on `PaymentOrderRepository`

For whoever owns `com.jachai.billing` / `com.jachai.platform`. Not fixed here —
this branch does not own those packages and does not touch the main folder.

## Where it is

- Fails from `E:\Advance Java\jachAI\target\classes`, i.e. the **main folder**,
  currently on the **`support-center`** branch.
- `git grep` confirms: both methods exist on `support-center` and **do not exist
  on `main`**. This arrived with the support-center work.

```
PaymentOrderRepository.java:23  BigDecimal sumAmountBdtByStatusAndCreatedAtBetween(...)
PaymentOrderRepository.java:25  BigDecimal sumAmountBdtByStatusAndProviderAndCreatedAtBetween(...)
```

## What is actually wrong

The exception blames the JDK:

```
InaccessibleObjectException: Unable to make field private final
java.math.BigInteger java.math.BigDecimal.intVal accessible:
module java.base does not "opens java.math" to unnamed module
```

That is a symptom, not the cause. **Spring Data MongoDB has no derived `sum…`
query.** `sum` is not a subject keyword its `PartTree` parser knows, so the method
is parsed as an ordinary finder and the declared return type, `BigDecimal`, is
taken to be the domain type. Spring then builds a `PersistentEntity` for
`BigDecimal` and walks its private fields — `intVal` — which the module system
blocks.

### Do not "fix" this with `--add-opens`

`--add-opens java.base/java.math=ALL-UNNAMED` makes the stack trace disappear and
leaves the real bug in place: Spring would then happily treat `BigDecimal` as a
Mongo document type and the query would still be meaningless. The startup error is
the useful part.

## Why the tests did not catch it

`PlatformOperationsServiceTests` mocks `PaymentOrderRepository`, so the real
Spring Data proxy is never created and the query is never parsed. A derived query
is only validated when the repository bean is built. Worth an integration test
that starts the context.

## The fix

Replace both derived methods with explicit aggregations. A small projection type
is used rather than returning `BigDecimal` directly, so nothing depends on scalar
extraction semantics.

```java
public interface PaymentOrderRepository extends MongoRepository<PaymentOrder, String> {

    /** One row, holding the summed amount. Absent when nothing matched. */
    record AmountTotal(BigDecimal total) { }

    @Aggregation(pipeline = {
        "{ $match: { status: ?0, createdAt: { $gte: ?1, $lt: ?2 } } }",
        "{ $group: { _id: null, total: { $sum: '$amountBdt' } } }"
    })
    Optional<AmountTotal> sumAmountBdtByStatusAndCreatedAt(
            PaymentOrderStatus status, Instant from, Instant to);

    @Aggregation(pipeline = {
        "{ $match: { status: ?0, provider: ?1, createdAt: { $gte: ?2, $lt: ?3 } } }",
        "{ $group: { _id: null, total: { $sum: '$amountBdt' } } }"
    })
    Optional<AmountTotal> sumAmountBdtByStatusAndProviderAndCreatedAt(
            PaymentOrderStatus status, PaymentProvider provider, Instant from, Instant to);
}
```

Call sites in `PlatformOperationsService` (lines 92, 99, 106, 121) then become:

```java
BigDecimal totalAmount = paymentOrders
        .sumAmountBdtByStatusAndCreatedAt(applied, effectiveFrom, effectiveTo)
        .map(PaymentOrderRepository.AmountTotal::total)
        .orElse(BigDecimal.ZERO);
```

`orElse(BigDecimal.ZERO)` matters: `$group` emits no document at all when nothing
matches, so the old code's implicit null would have become a `NullPointerException`
on the first empty window.

## Two things to check while you are in there

1. **The window is now half-open, `$gte … $lt`.** Line 121 sums consecutive time
   buckets in a loop. With an inclusive upper bound an order landing exactly on a
   boundary is counted in two buckets, so the totals would not add up to the
   overall figure. Half-open is almost certainly what was meant; confirm it is.

2. **How is `amountBdt` actually stored?** Spring Data MongoDB has historically
   written `BigDecimal` as a **string** for backwards compatibility rather than
   `Decimal128`. `$sum` over strings returns `0` silently — no error, just a wrong
   number on a billing screen. Check one real document:

   ```js
   db.paymentOrders.findOne({}, { amountBdt: 1 })
   ```

   If it comes back `"1500.00"` rather than `NumberDecimal("1500.00")`, the
   aggregation needs a `$toDecimal` in the `$group`, or the representation needs
   configuring, or the sum has to be done in Java.

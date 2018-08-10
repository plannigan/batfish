package org.batfish.datamodel.answers;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ColumnAggregation {

  public static final String PROP_AGGREGATION = "aggregation";

  public static final String PROP_COLUMN = "column";

  @JsonCreator
  private static @Nonnull ColumnAggregation create(
      @JsonProperty(PROP_AGGREGATION) Aggregation aggregation,
      @JsonProperty(PROP_COLUMN) String column) {
    return new ColumnAggregation(requireNonNull(aggregation), requireNonNull(column));
  }

  private final Aggregation _aggregation;

  private final String _column;

  public ColumnAggregation(@Nonnull Aggregation aggregation, @Nonnull String column) {
    _aggregation = aggregation;
    _column = column;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ColumnAggregation)) {
      return false;
    }
    ColumnAggregation rhs = (ColumnAggregation) obj;
    return _aggregation == rhs._aggregation && _column.equals(rhs._column);
  }

  @Override
  public int hashCode() {
    return Objects.hash(_aggregation.ordinal(), _column);
  }

  @JsonProperty(PROP_AGGREGATION)
  public @Nonnull Aggregation getAggregation() {
    return _aggregation;
  }

  @JsonProperty(PROP_COLUMN)
  public @Nonnull String getColumn() {
    return _column;
  }

  @Override
  public String toString() {
    return toStringHelper(getClass())
        .add(PROP_AGGREGATION, _aggregation)
        .add(PROP_COLUMN, _column)
        .toString();
  }
}

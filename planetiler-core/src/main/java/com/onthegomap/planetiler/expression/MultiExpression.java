package com.onthegomap.planetiler.expression;

import static com.onthegomap.planetiler.expression.Expression.FALSE;
import static com.onthegomap.planetiler.expression.Expression.TRUE;
import static com.onthegomap.planetiler.expression.Expression.matchType;
import static com.onthegomap.planetiler.geo.GeoUtils.EMPTY_GEOMETRY;

import com.onthegomap.planetiler.reader.SimpleFeature;
import com.onthegomap.planetiler.reader.SourceFeature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A list of {@link Expression Expressions} to evaluate on input elements.
 * <p>
 * {@link #index()} returns an optimized {@link Index} that evaluates the minimal set of expressions on the keys present
 * on the element.
 * <p>
 * {@link Index#getMatches(SourceFeature)} returns the data value associated with the expressions that match an input
 * element.
 *
 * @param <T> type of data value associated with each expression
 */
public record MultiExpression<T>(List<Entry<T>> expressions) {

  public static <T> MultiExpression<T> of(List<Entry<T>> expressions) {
    return new MultiExpression<>(expressions);
  }

  public static <T> Entry<T> entry(T result, Expression expression) {
    return new Entry<>(result, expression);
  }

  /**
   * Evaluates a list of expressions on an input element, storing the matches into {@code result} and using {@code
   * visited} to avoid evaluating an expression more than once.
   */
  private static <T> void visitExpressions(SourceFeature input, List<Match<T>> result,
    boolean[] visited, List<EntryWithId<T>> expressions) {
    if (expressions != null) {
      for (EntryWithId<T> expressionValue : expressions) {
        if (!visited[expressionValue.id]) {
          visited[expressionValue.id] = true;
          List<String> matchKeys = new ArrayList<>();
          if (expressionValue.expression().evaluate(input, matchKeys)) {
            result.add(new Match<>(expressionValue.result, matchKeys));
          }
        }
      }
    }
  }

  /** Calls {@code acceptKey} for every tag that could possibly cause {@code exp} to match an input element. */
  private static void getRelevantKeys(Expression exp, Consumer<String> acceptKey) {
    if (exp instanceof Expression.And and) {
      and.children().forEach(child -> getRelevantKeys(child, acceptKey));
    } else if (exp instanceof Expression.Or or) {
      or.children().forEach(child -> getRelevantKeys(child, acceptKey));
    } else if (exp instanceof Expression.Not) {
      // ignore anything that's purely used as a filter
    } else if (exp instanceof Expression.MatchField field) {
      acceptKey.accept(field.field());
    } else if (exp instanceof Expression.MatchAny any) {
      acceptKey.accept(any.field());
    }
  }

  /**
   * Calls {@code acceptKey} for every tag that, when missing, could possibly cause {@code exp} to match an input
   * element.
   */
  private static void getRelevantMissingKeys(Expression exp, Consumer<String> acceptKey) {
    if (exp instanceof Expression.And and) {
      and.children().forEach(child -> getRelevantKeys(child, acceptKey));
    } else if (exp instanceof Expression.Or or) {
      or.children().forEach(child -> getRelevantKeys(child, acceptKey));
    } else if (exp instanceof Expression.Not) {
      // ignore anything that's purely used as a filter
    } else if (exp instanceof Expression.MatchAny any && any.matchWhenMissing()) {
      acceptKey.accept(any.field());
    }
  }

  /** Returns an optimized index for matching {@link #expressions()} against each input element. */
  public Index<T> index() {
    if (expressions.isEmpty()) {
      return new EmptyIndex<>();
    }
    boolean caresAboutGeometryType = expressions.stream().anyMatch(entry ->
      entry.expression.contains(exp -> exp instanceof Expression.MatchType));
    return caresAboutGeometryType ? new GeometryTypeIndex<>(this) : new KeyIndex<>(this);
  }

  /** Returns a copy of this multi-expression that replaces every expression using {@code mapper}. */
  public MultiExpression<T> map(Function<Expression, Expression> mapper) {
    return new MultiExpression<>(
      expressions.stream()
        .map(entry -> entry(entry.result, mapper.apply(entry.expression).simplify()))
        .filter(entry -> entry.expression != Expression.FALSE)
        .toList()
    );
  }

  /**
   * Returns a copy of this multi-expression that replaces every sub-expression that matches {@code test} with {@code
   * b}.
   */
  public MultiExpression<T> replace(Predicate<Expression> test, Expression b) {
    return map(e -> e.replace(test, b));
  }

  /**
   * Returns a copy of this multi-expression that replaces every sub-expression equal to {@code a} with {@code b}.
   */
  public MultiExpression<T> replace(Expression a, Expression b) {
    return map(e -> e.replace(a, b));
  }

  /** Returns a copy of this multi-expression with each expression simplified. */
  public MultiExpression<T> simplify() {
    return map(e -> e.simplify());
  }

  /** Returns a copy of this multi-expression, filtering-out the entry for each data value matching {@code accept}. */
  public MultiExpression<T> filterResults(Predicate<T> accept) {
    return new MultiExpression<>(
      expressions.stream()
        .filter(entry -> accept.test(entry.result))
        .toList()
    );
  }

  /** Returns a copy of this multi-expression, replacing the data value with {@code fn}. */
  public <U> MultiExpression<U> mapResults(Function<T, U> fn) {
    return new MultiExpression<>(
      expressions.stream()
        .map(entry -> entry(fn.apply(entry.result), entry.expression))
        .toList()
    );
  }

  /**
   * An optimized index for finding which expressions match an input element.
   *
   * @param <T> type of data value associated with each expression
   */
  public interface Index<T> {

    List<Match<T>> getMatchesWithTriggers(SourceFeature input);

    /** Returns all data values associated with expressions that match an input element. */
    default List<T> getMatches(SourceFeature input) {
      List<Match<T>> matches = getMatchesWithTriggers(input);
      return matches.stream().map(d -> d.match).toList();
    }

    /**
     * Returns the data value associated with the first expression that match an input element, or {@code defaultValue}
     * if none match.
     */
    default T getOrElse(SourceFeature input, T defaultValue) {
      List<T> matches = getMatches(input);
      return matches.isEmpty() ? defaultValue : matches.get(0);
    }

    /**
     * Returns the data value associated with expressions matching a feature with {@code tags}.
     */
    default T getOrElse(Map<String, Object> tags, T defaultValue) {
      List<T> matches = getMatches(SimpleFeature.create(EMPTY_GEOMETRY, tags));
      return matches.isEmpty() ? defaultValue : matches.get(0);
    }

    /** Returns true if any expression matches that tags from an input element. */
    default boolean matches(SourceFeature input) {
      return !getMatchesWithTriggers(input).isEmpty();
    }

    default boolean isEmpty() {
      return false;
    }
  }

  private static class EmptyIndex<T> implements Index<T> {

    @Override
    public List<Match<T>> getMatchesWithTriggers(SourceFeature input) {
      return List.of();
    }

    @Override
    public boolean isEmpty() {
      return true;
    }
  }

  /** Index that limits the search space of expressions based on keys present on an input element. */
  private static class KeyIndex<T> implements Index<T> {

    private final int numExpressions;
    // index from source feature tag key to the expressions that include it so that
    // we can limit the number of expressions we need to evaluate for each input,
    // improves matching performance by ~5x
    private final Map<String, List<EntryWithId<T>>> keyToExpressionsMap;
    // same as keyToExpressionsMap but as a list (optimized for iteration when # source feature keys > # tags we care about)
    private final List<Map.Entry<String, List<EntryWithId<T>>>> keyToExpressionsList;
    // expressions that should match when certain tags are *not* present on an input element
    private final List<Map.Entry<String, List<EntryWithId<T>>>> missingKeyToExpressionList;

    private KeyIndex(MultiExpression<T> expressions) {
      AtomicInteger ids = new AtomicInteger();
      // build the indexes
      Map<String, Set<EntryWithId<T>>> keyToExpressions = new HashMap<>();
      Map<String, Set<EntryWithId<T>>> missingKeyToExpressions = new HashMap<>();
      for (var entry : expressions.expressions) {
        Expression expression = entry.expression;
        EntryWithId<T> expressionValue = new EntryWithId<>(entry.result, expression, ids.incrementAndGet());
        getRelevantKeys(expression,
          key -> keyToExpressions.computeIfAbsent(key, k -> new HashSet<>()).add(expressionValue));
        getRelevantMissingKeys(expression,
          key -> missingKeyToExpressions.computeIfAbsent(key, k -> new HashSet<>()).add(expressionValue));
      }
      keyToExpressionsMap = new HashMap<>();
      keyToExpressions.forEach((key, value) -> keyToExpressionsMap.put(key, value.stream().toList()));
      keyToExpressionsList = keyToExpressionsMap.entrySet().stream().toList();
      missingKeyToExpressionList = missingKeyToExpressions.entrySet().stream()
        .map(entry -> Map.entry(entry.getKey(), entry.getValue().stream().toList())).toList();
      numExpressions = ids.incrementAndGet();
    }

    /** Lookup matches in this index for expressions that match a certain type. */
    @Override
    public List<Match<T>> getMatchesWithTriggers(SourceFeature input) {
      List<Match<T>> result = new ArrayList<>();
      boolean[] visited = new boolean[numExpressions];
      for (var entry : missingKeyToExpressionList) {
        if (!input.hasTag(entry.getKey())) {
          visitExpressions(input, result, visited, entry.getValue());
        }
      }
      Map<String, Object> tags = input.tags();
      if (tags.size() < keyToExpressionsMap.size()) {
        for (String inputKey : tags.keySet()) {
          visitExpressions(input, result, visited, keyToExpressionsMap.get(inputKey));
        }
      } else {
        for (var entry : keyToExpressionsList) {
          if (tags.containsKey(entry.getKey())) {
            visitExpressions(input, result, visited, entry.getValue());
          }
        }
      }
      return result;
    }
  }

  /** Index that limits the search space of expressions based on geometry type of an input element. */
  private static class GeometryTypeIndex<T> implements Index<T> {

    private final KeyIndex<T> pointIndex;
    private final KeyIndex<T> lineIndex;
    private final KeyIndex<T> polygonIndex;

    private GeometryTypeIndex(MultiExpression<T> expressions) {
      // build an index per type then search in each of those indexes based on the geometry type of each input element
      // this narrows the search space substantially, improving matching performance
      pointIndex = indexForType(expressions, Expression.POINT_TYPE);
      lineIndex = indexForType(expressions, Expression.LINESTRING_TYPE);
      polygonIndex = indexForType(expressions, Expression.POLYGON_TYPE);
    }

    private KeyIndex<T> indexForType(MultiExpression<T> expressions, String type) {
      return new KeyIndex<>(expressions
        .replace(matchType(type), TRUE)
        .replace(e -> e instanceof Expression.MatchType, FALSE)
        .simplify());
    }

    /**
     * Returns all data values associated with expressions that match an input element, along with the tag keys that
     * caused the match.
     */
    public List<Match<T>> getMatchesWithTriggers(SourceFeature input) {
      List<Match<T>> result;
      if (input.isPoint()) {
        result = pointIndex.getMatchesWithTriggers(input);
      } else if (input.canBeLine()) {
        result = lineIndex.getMatchesWithTriggers(input);
        // closed ways can be lines or polygons, unless area=yes or no
        if (input.canBePolygon()) {
          result.addAll(polygonIndex.getMatchesWithTriggers(input));
        }
      } else if (input.canBePolygon()) {
        result = polygonIndex.getMatchesWithTriggers(input);
      } else {
        result = pointIndex.getMatchesWithTriggers(input);
      }
      return result;
    }
  }

  /** An expression/value pair with unique ID to store whether we evaluated it yet. */
  private static record EntryWithId<T>(T result, Expression expression, int id) {}

  /**
   * An {@code expression} to evaluate on input elements and {@code result} value to return when the element matches.
   */
  public static record Entry<T>(T result, Expression expression) {}

  /** The result when an expression matches, along with the input element tag {@code keys} that triggered the match. */
  public static record Match<T>(T match, List<String> keys) {}
}

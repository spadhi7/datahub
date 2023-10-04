package com.linkedin.metadata.search;

import com.codahale.metrics.Timer;
import com.linkedin.data.template.LongMap;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.search.cache.EntityDocCountCache;
import com.linkedin.metadata.search.client.CachingEntitySearchService;
import com.linkedin.metadata.search.ranker.SearchRanker;
import com.linkedin.metadata.utils.SearchUtil;
import com.linkedin.metadata.utils.metrics.MetricUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import static com.linkedin.metadata.utils.SearchUtil.*;


@Slf4j
public class SearchService {
  private final CachingEntitySearchService _cachingEntitySearchService;
  private final EntityDocCountCache _entityDocCountCache;
  private final SearchRanker _searchRanker;

  public SearchService(
      EntityDocCountCache entityDocCountCache,
      CachingEntitySearchService cachingEntitySearchService,
      SearchRanker searchRanker) {
    _cachingEntitySearchService = cachingEntitySearchService;
    _searchRanker = searchRanker;
    _entityDocCountCache = entityDocCountCache;
  }

  public Map<String, Long> docCountPerEntity(@Nonnull List<String> entityNames) {
    return entityNames.stream()
        .collect(Collectors.toMap(Function.identity(),
            entityName -> _entityDocCountCache.getEntityDocCount().getOrDefault(entityName.toLowerCase(), 0L)));
  }

  /**
   * Gets a list of documents that match given search request. The results are aggregated and filters are applied to the
   * search hits and not the aggregation results.
   *
   * @param entityNames names of the entities
   * @param input the search input text
   * @param postFilters the request map with fields and values as filters to be applied to search hits
   * @param sortCriterion {@link SortCriterion} to be applied to search results
   * @param from index to start the search from
   * @param size the number of search hits to return
   * @param searchFlags optional set of flags to control search behavior
   * @return a {@link SearchResult} that contains a list of matched documents and related search result metadata
   */
  @Nonnull
  public SearchResult search(@Nonnull List<String> entityNames, @Nonnull String input, @Nullable Filter postFilters,
      @Nullable SortCriterion sortCriterion, int from, int size, @Nullable SearchFlags searchFlags) {
    List<String> entitiesToSearch = getEntitiesToSearch(entityNames);
    if (entitiesToSearch.isEmpty()) {
      // Optimization: If the indices are all empty, return empty result
      return getEmptySearchResult(from, size);
    }
    SearchResult result =
        _cachingEntitySearchService.search(entitiesToSearch, input, postFilters, sortCriterion, from, size, searchFlags, null);

    try {
      return result.copy().setEntities(new SearchEntityArray(_searchRanker.rank(result.getEntities())));
    } catch (Exception e) {
      log.error("Failed to rank: {}, exception - {}", result, e.toString());
      throw new RuntimeException("Failed to rank " + result.toString());
    }
  }

  @Nonnull
  public SearchResult searchAcrossEntities(@Nonnull List<String> entities, @Nonnull String input,
      @Nullable Filter postFilters, @Nullable SortCriterion sortCriterion, int from, int size,
      @Nullable SearchFlags searchFlags) {
    return searchAcrossEntities(entities, input, postFilters, sortCriterion, from, size, searchFlags, null);
  }

  /**
   * Gets a list of documents that match given search request across multiple entities. The results are aggregated and filters are applied to the
   * search hits and not the aggregation results.
   *
   * @param entities list of entities to search (If empty, searches across all entities)
   * @param input the search input text
   * @param postFilters the request map with fields and values as filters to be applied to search hits
   * @param sortCriterion {@link SortCriterion} to be applied to search results
   * @param from index to start the search from
   * @param size the number of search hits to return
   * @param searchFlags optional set of flags to control search behavior
   * @param facets list of facets we want aggregations for
   * @return a {@link SearchResult} that contains a list of matched documents and related search result metadata
   */
  @Nonnull
  public SearchResult searchAcrossEntities(@Nonnull List<String> entities, @Nonnull String input,
      @Nullable Filter postFilters, @Nullable SortCriterion sortCriterion, int from, int size,
      @Nullable SearchFlags searchFlags, @Nullable List<String> facets) {
    log.debug(String.format(
        "Searching Search documents entities: %s, input: %s, postFilters: %s, sortCriterion: %s, from: %s, size: %s",
        entities, input, postFilters, sortCriterion, from, size));
    // DEPRECATED
    // This is the legacy version of `_entityType`-- it operates as a special case and does not support ORs, Unions, etc.
    // We will still provide it for backwards compatibility but when sending filters to the backend use the new
    // filter name `_entityType` that we provide above. This is just provided to prevent a breaking change for old clients.
    boolean aggregateByLegacyEntityFacet = facets != null && facets.contains("entity");
    if (aggregateByLegacyEntityFacet) {
      facets = new ArrayList<>(facets);
      facets.add(INDEX_VIRTUAL_FIELD);
    }
    List<String> nonEmptyEntities = getEntitiesToSearch(entities);
    if (nonEmptyEntities.isEmpty()) {
      // Optimization: If the indices are all empty, return empty result
      return getEmptySearchResult(from, size);
    }
    SearchResult result = _cachingEntitySearchService.search(nonEmptyEntities, input, postFilters, sortCriterion, from, size, searchFlags, facets);
    if (facets == null || facets.contains("entity") || facets.contains("_entityType")) {
      Optional<AggregationMetadata> entityTypeAgg = result.getMetadata().getAggregations().stream().filter(
          aggMeta -> aggMeta.getName().equals(INDEX_VIRTUAL_FIELD)).findFirst();
      if (entityTypeAgg.isPresent()) {
        LongMap numResultsPerEntity = entityTypeAgg.get().getAggregations();
        result.getMetadata()
            .getAggregations()
            .add(new AggregationMetadata().setName("entity")
                .setDisplayName("Type")
                .setAggregations(numResultsPerEntity)
                .setFilterValues(new FilterValueArray(SearchUtil.convertToFilters(numResultsPerEntity, Collections.emptySet()))));
      } else {
        // Should not happen due to the adding of the _entityType aggregation before, but if it does, best-effort count of entity types
        // Will not include entity types that had 0 results
        Map<String, Long> numResultsPerEntity = result.getEntities().stream().collect(Collectors.groupingBy(
            entity -> entity.getEntity().getEntityType(), Collectors.counting()));
        result.getMetadata()
            .getAggregations()
            .add(new AggregationMetadata().setName("entity")
                .setDisplayName("Type")
                .setAggregations(new LongMap(numResultsPerEntity))
                .setFilterValues(new FilterValueArray(SearchUtil.convertToFilters(numResultsPerEntity, Collections.emptySet()))));
      }
    }
    return result;
  }

  private List<String> getEntitiesToSearch(@Nonnull List<String> inputEntities) {
    List<String> nonEmptyEntities;
    List<String> lowercaseEntities = inputEntities.stream().map(String::toLowerCase).collect(Collectors.toList());
    try (Timer.Context ignored = MetricUtils.timer(this.getClass(), "getNonEmptyEntities").time()) {
      nonEmptyEntities = _entityDocCountCache.getNonEmptyEntities();
    }
    if (!inputEntities.isEmpty()) {
      nonEmptyEntities = nonEmptyEntities.stream().filter(lowercaseEntities::contains).collect(Collectors.toList());
    }
    return nonEmptyEntities;
  }

  /**
   * Gets a list of documents that match given search request across multiple entities. The results are aggregated and filters are applied to the
   * search hits and not the aggregation results.
   *
   * @param entities list of entities to search (If empty, searches across all entities)
   * @param input the search input text
   * @param postFilters the request map with fields and values as filters to be applied to search hits
   * @param sortCriterion {@link SortCriterion} to be applied to search results
   * @param scrollId opaque scroll identifier for passing to search backend
   * @param size the number of search hits to return
   * @param searchFlags optional set of flags to control search behavior
   * @return a {@link ScrollResult} that contains a list of matched documents and related search result metadata
   */
  @Nonnull
  public ScrollResult scrollAcrossEntities(@Nonnull List<String> entities, @Nonnull String input,
      @Nullable Filter postFilters, @Nullable SortCriterion sortCriterion, @Nullable String scrollId, @Nullable String keepAlive,
      int size, @Nullable SearchFlags searchFlags) {
    log.debug(String.format(
        "Searching Search documents entities: %s, input: %s, postFilters: %s, sortCriterion: %s, from: %s, size: %s",
        entities, input, postFilters, sortCriterion, scrollId, size));
    List<String> entitiesToSearch = getEntitiesToSearch(entities);
    if (entitiesToSearch.isEmpty()) {
      // No indices with non-zero entries: skip querying and return empty result
      return getEmptyScrollResult(size);
    }
    return _cachingEntitySearchService.scroll(entitiesToSearch, input, postFilters, sortCriterion, scrollId, keepAlive, size, searchFlags);
  }

  private static SearchResult getEmptySearchResult(int from, int size) {
    return new SearchResult().setEntities(new SearchEntityArray())
        .setNumEntities(0)
        .setFrom(from)
        .setPageSize(size)
        .setMetadata(new SearchResultMetadata().setAggregations(new AggregationMetadataArray()));
  }

  private static ScrollResult getEmptyScrollResult(int size) {
    return new ScrollResult().setEntities(new SearchEntityArray())
        .setNumEntities(0)
        .setPageSize(size)
        .setMetadata(new SearchResultMetadata().setAggregations(new AggregationMetadataArray()));
  }
}

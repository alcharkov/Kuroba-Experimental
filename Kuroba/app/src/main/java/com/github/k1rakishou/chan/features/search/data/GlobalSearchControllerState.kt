package com.github.k1rakishou.chan.features.search.data

import com.github.k1rakishou.chan.core.site.SiteIcon
import com.github.k1rakishou.chan.core.site.sites.search.SearchBoard
import com.github.k1rakishou.chan.core.site.sites.search.SiteGlobalSearchType
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor

internal sealed class GlobalSearchControllerState {
  object Uninitialized : GlobalSearchControllerState()
  object Loading : GlobalSearchControllerState()
  object Empty : GlobalSearchControllerState()
  data class Error(val errorText: String) : GlobalSearchControllerState()
  data class Data(val data: GlobalSearchControllerStateData) : GlobalSearchControllerState()
}

internal data class SelectedSite(
  val siteDescriptor: SiteDescriptor,
  val siteIcon: SiteIcon,
  val siteGlobalSearchType: SiteGlobalSearchType
)

internal data class SitesWithSearch(
  val sites: List<SiteDescriptor>,
  val selectedSite: SelectedSite
)

internal data class GlobalSearchControllerStateData(
  val sitesWithSearch: SitesWithSearch,
  val searchParameters: SearchParameters
)

sealed class SearchParameters {
  abstract val query: String

  abstract fun isValid(): Boolean
  abstract fun assertValid()

  abstract fun getCurrentQuery(): String

  data class SimpleQuerySearchParameters(
    override val query: String
  ) : SearchParameters() {

    override fun getCurrentQuery(): String {
      return query
    }

    override fun isValid(): Boolean {
      if (query.length >= MIN_SEARCH_QUERY_LENGTH) {
        return true
      }

      return false
    }

    override fun assertValid() {
      if (isValid()) {
        return
      }

      throw IllegalStateException("SimpleQuerySearchParameters are not valid! query='$query'")
    }

  }

  abstract class AdvancedSearchParameters(
    override val query: String,
    val subject: String,
    val searchBoard: SearchBoard?
  ) : SearchParameters() {

    override fun getCurrentQuery(): String {
      return buildString {
        if (searchBoard != null) {
          append("/${searchBoard.boardCode()}/")
        }

        if (subject.isNotEmpty()) {
          if (isNotEmpty()) {
            append(" ")
          }

          append("Subject: '$subject'")
        }

        if (query.isNotEmpty()) {
          if (isNotEmpty()) {
            append(" ")
          }

          append("Comment: '$query'")
        }
      }
    }

    override fun isValid(): Boolean {
      if (searchBoard == null) {
        return false
      }

      var valid = false

      valid = valid or (query.length >= MIN_SEARCH_QUERY_LENGTH)
      valid = valid or (subject.length >= MIN_SEARCH_QUERY_LENGTH)

      return valid
    }

    override fun assertValid() {
      if (isValid()) {
        return
      }

      throw IllegalStateException("FoolFuukaSearchParameters are not valid! " +
        "query='$query', subject='$subject', searchBoard='$searchBoard'")
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as AdvancedSearchParameters

      if (query != other.query) return false
      if (subject != other.subject) return false
      if (searchBoard != other.searchBoard) return false

      return true
    }

    override fun hashCode(): Int {
      var result = query.hashCode()
      result = 31 * result + subject.hashCode()
      result = 31 * result + (searchBoard?.hashCode() ?: 0)
      return result
    }

    override fun toString(): String {
      return "AdvancedSearchParameters(type='${this.javaClass.simpleName}', query='$query', " +
        "subject='$subject', searchBoard=$searchBoard)"
    }

  }

  class FuukaSearchParameters(
    query: String,
    subject: String,
    searchBoard: SearchBoard?
  ) : AdvancedSearchParameters(query, subject, searchBoard)

  class FoolFuukaSearchParameters(
    query: String,
    subject: String,
    searchBoard: SearchBoard?
  ) : AdvancedSearchParameters(query, subject, searchBoard)

  companion object {
    const val MIN_SEARCH_QUERY_LENGTH = 2
  }
}
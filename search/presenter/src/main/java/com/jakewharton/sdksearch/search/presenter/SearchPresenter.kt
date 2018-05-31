package com.jakewharton.sdksearch.search.presenter

import com.jakewharton.sdksearch.search.presenter.SearchPresenter.Model.SyncStatus
import com.jakewharton.sdksearch.store.Item
import com.jakewharton.sdksearch.store.ItemStore
import com.jakewharton.sdksearch.sync.ItemSynchronizer
import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.select
import java.util.concurrent.TimeUnit

class SearchPresenter(
  private val context: CoroutineDispatcher,
  private val store: ItemStore,
  private val synchronizer: ItemSynchronizer
) {
  private val _models = ConflatedBroadcastChannel<Model>()
  val models: ReceiveChannel<Model> get() = _models.openSubscription()

  private val _events = RendezvousChannel<Event>()
  val events: SendChannel<Event> get() = _events

  fun start(): Job {
    val itemCount = store.count()

    val job = launch(context, UNDISPATCHED) {
      var model = Model()
      var activeQuery = ""
      var activeQueryResults: ReceiveChannel<List<Item>>? = null
      var activeQueryJob: Job? = null
      while (isActive) {
        model = select {
          itemCount.onReceive {
            model.copy(count = it)
          }
          activeQueryResults?.onReceive {
            model.copy(queryResults = Model.QueryResults(activeQuery, it))
          }
          synchronizer.state.onReceive {
            model.copy(syncStatus = when (it) {
              ItemSynchronizer.SyncStatus.IDLE -> SyncStatus.IDLE
              ItemSynchronizer.SyncStatus.SYNC -> SyncStatus.SYNC
              ItemSynchronizer.SyncStatus.FAILED -> SyncStatus.FAILED
            })
          }
          _events.onReceive {
            when (it) {
              is Event.ClearSyncStatus -> {
                model.copy(syncStatus = SyncStatus.IDLE)
              }
              is Event.QueryChanged -> {
                if (it.query != activeQuery) {
                  activeQueryJob?.cancel()
                  if (it.query == "") {
                    return@onReceive model.copy(queryResults = Model.QueryResults("", emptyList()))
                  }

                  activeQueryJob = launch(context) {
                    delay(200, TimeUnit.MILLISECONDS)
                    activeQuery = it.query
                    activeQueryResults = store.queryItems(it.query)
                  }
                }
                return@onReceive model
              }
            }
          }
        }
        // TODO debounce
        _models.offer(model)
      }
    }

    synchronizer.forceSync()

    return job
  }

  sealed class Event {
    data class QueryChanged(val query: String) : Event()
    object ClearSyncStatus : Event()
  }

  data class Model(
    val count: Long = 0,
    val queryResults: QueryResults = QueryResults(),
    val syncStatus: SyncStatus = SyncStatus.IDLE
  ) {
    data class QueryResults(
      val query: String = "",
      val items: List<Item> = emptyList()
    )
    enum class SyncStatus {
      IDLE, SYNC, FAILED
    }
  }
}

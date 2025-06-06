package org.thoughtcrime.securesms.stories.landing

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.app.SharedElementCallback
import androidx.core.view.ViewCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import androidx.transition.TransitionInflater
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.subscribeBy
import kotlinx.coroutines.launch
import org.signal.core.util.concurrent.LifecycleDisposable
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.banner.BannerManager
import org.thoughtcrime.securesms.banner.banners.DeprecatedBuildBanner
import org.thoughtcrime.securesms.banner.banners.UnauthorizedBanner
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragment
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.StoryViewState
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.main.MainNavigationDestination
import org.thoughtcrime.securesms.main.MainToolbarMode
import org.thoughtcrime.securesms.main.MainToolbarViewModel
import org.thoughtcrime.securesms.main.Material3OnScrollHelperBinder
import org.thoughtcrime.securesms.mediasend.camerax.CameraXUtil
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.safety.SafetyNumberBottomSheet
import org.thoughtcrime.securesms.stories.StoryTextPostModel
import org.thoughtcrime.securesms.stories.StoryViewerArgs
import org.thoughtcrime.securesms.stories.dialogs.StoryContextMenu
import org.thoughtcrime.securesms.stories.dialogs.StoryDialogs
import org.thoughtcrime.securesms.stories.my.MyStoriesActivity
import org.thoughtcrime.securesms.stories.tabs.ConversationListTabsViewModel
import org.thoughtcrime.securesms.stories.viewer.StoryViewerActivity
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.fragments.requireListener
import org.thoughtcrime.securesms.util.views.Stub
import org.thoughtcrime.securesms.util.visible
import java.util.concurrent.TimeUnit

/**
 * The "landing page" for Stories.
 */
class StoriesLandingFragment : DSLSettingsFragment(layoutId = R.layout.stories_landing_fragment) {

  companion object {
    private const val LIST_SMOOTH_SCROLL_TO_TOP_THRESHOLD = 25
  }

  private lateinit var emptyNotice: View
  private lateinit var cameraFab: FloatingActionButton

  private lateinit var bannerView: Stub<ComposeView>

  private val lifecycleDisposable = LifecycleDisposable()

  private val viewModel: StoriesLandingViewModel by viewModels(
    factoryProducer = {
      StoriesLandingViewModel.Factory(StoriesLandingRepository(requireContext()))
    }
  )

  private val tabsViewModel: ConversationListTabsViewModel by viewModels(ownerProducer = { requireActivity() })
  private val mainToolbarViewModel: MainToolbarViewModel by activityViewModels()

  private lateinit var adapter: MappingAdapter

  override fun onResume() {
    super.onResume()
    viewModel.isTransitioningToAnotherScreen = false
    initializeSearchAction()
    viewModel.markStoriesRead()

    AppDependencies.expireStoriesManager.scheduleIfNecessary()
  }

  private fun initializeSearchAction() {
    lifecycleDisposable += mainToolbarViewModel.getSearchEventsFlowable().subscribeBy {
      when (it) {
        MainToolbarViewModel.Event.Search.Close -> {
          viewModel.setSearchQuery("")
        }
        MainToolbarViewModel.Event.Search.Open -> {
          mainToolbarViewModel.setSearchHint(R.string.SearchToolbar_search)
        }
        is MainToolbarViewModel.Event.Search.Query -> {
          viewModel.setSearchQuery(it.query.trim())
        }
      }
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    bannerView = ViewUtil.findStubById(view, R.id.banner_stub)
    initializeBanners()
  }

  private fun initializeBanners() {
    val bannerManager = BannerManager(
      banners = listOf(
        DeprecatedBuildBanner(),
        UnauthorizedBanner(requireContext())
      ),
      onNewBannerShownListener = {
        if (bannerView.resolved()) {
          bannerView.get().addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            recyclerView?.setPadding(0, bottom - top, 0, 0)
          }
          recyclerView?.clipToPadding = false
        }
      },
      onNoBannerShownListener = {
        recyclerView?.clipToPadding = true
      }
    )
    bannerManager.updateContent(bannerView.get())
  }

  override fun bindAdapter(adapter: MappingAdapter) {
    this.adapter = adapter

    StoriesLandingItem.register(adapter)
    MyStoriesItem.register(adapter)
    ExpandHeader.register(adapter)

    requireListener<Material3OnScrollHelperBinder>().bindScrollHelper(recyclerView!!, viewLifecycleOwner)

    lifecycleDisposable.bindTo(viewLifecycleOwner)
    emptyNotice = requireView().findViewById(R.id.empty_notice)
    cameraFab = requireView().findViewById(R.id.camera_fab)
    val sharedElementTarget: View = requireView().findViewById(R.id.camera_fab_shared_element_target)

    ViewCompat.setTransitionName(cameraFab, "new_convo_fab")
    ViewCompat.setTransitionName(sharedElementTarget, "camera_fab")

    sharedElementEnterTransition = TransitionInflater.from(requireContext()).inflateTransition(R.transition.change_transform_fabs)
    setEnterSharedElementCallback(object : SharedElementCallback() {
      override fun onSharedElementStart(sharedElementNames: MutableList<String>?, sharedElements: MutableList<View>?, sharedElementSnapshots: MutableList<View>?) {
        if (sharedElementNames?.contains("camera_fab") == true) {
          cameraFab.setImageResource(R.drawable.symbol_edit_24)
          lifecycleDisposable += Single.timer(200, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy {
              cameraFab.setImageResource(R.drawable.symbol_camera_24)
              sharedElementTarget.alpha = 0f
            }
        }
      }
    })

    cameraFab.setOnClickListener {
      if (CameraXUtil.isSupported()) {
        startActivityIfAble(MediaSelectionActivity.camera(requireContext(), isStory = true))
      } else {
        Permissions.with(this)
          .request(Manifest.permission.CAMERA)
          .ifNecessary()
          .onAllGranted { startActivityIfAble(MediaSelectionActivity.camera(requireContext(), isStory = true)) }
          .withRationaleDialog(getString(R.string.CameraXFragment_allow_access_camera), getString(R.string.CameraXFragment_to_capture_photos_and_video_allow_camera), R.drawable.symbol_camera_24)
          .withPermanentDenialDialog(
            getString(R.string.CameraXFragment_signal_needs_camera_access_capture_photos),
            null,
            R.string.CameraXFragment_allow_access_camera,
            R.string.CameraXFragment_to_capture_photos_videos,
            getParentFragmentManager()
          )
          .onAnyDenied { Toast.makeText(requireContext(), R.string.CameraXFragment_signal_needs_camera_access_capture_photos, Toast.LENGTH_LONG).show() }
          .execute()
      }
    }

    viewModel.state.observe(viewLifecycleOwner) {
      if (it.loadingState == StoriesLandingState.LoadingState.LOADED) {
        adapter.submitList(getConfiguration(it).toMappingModelList())
        emptyNotice.visible = it.hasNoStories
      }
    }

    requireActivity().onBackPressedDispatcher.addCallback(
      viewLifecycleOwner,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (!closeSearchIfOpen()) {
            tabsViewModel.onChatsSelected()
          }
        }
      }
    )

    lifecycleDisposable += tabsViewModel.tabClickEvents
      .filter { it == MainNavigationDestination.STORIES }
      .subscribeBy(onNext = {
        val layoutManager = recyclerView?.layoutManager as? LinearLayoutManager ?: return@subscribeBy
        if (layoutManager.findFirstVisibleItemPosition() <= LIST_SMOOTH_SCROLL_TO_TOP_THRESHOLD) {
          recyclerView?.smoothScrollToPosition(0)
        } else {
          recyclerView?.scrollToPosition(0)
        }
      })

    this.adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
      override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
        (requireActivity() as? MainActivity)?.onFirstRender()
        this@StoriesLandingFragment.adapter.unregisterAdapterDataObserver(this)
      }
    })
  }

  private fun getConfiguration(state: StoriesLandingState): DSLConfiguration {
    return configure {
      val (stories, hidden) = state.storiesLandingItems.filter {
        if (state.searchQuery.isNotEmpty()) {
          val storyRecipientName = it.storyRecipient.getDisplayName(requireContext())
          val individualRecipientName = it.individualRecipient.getDisplayName(requireContext())

          storyRecipientName.contains(state.searchQuery, ignoreCase = true) || individualRecipientName.contains(state.searchQuery, ignoreCase = true)
        } else {
          true
        }
      }.map {
        createStoryLandingItem(it)
      }.partition {
        !it.data.isHidden
      }

      if (state.displayMyStoryItem) {
        customPref(
          MyStoriesItem.Model(
            onClick = {
              cameraFab.performClick()
            }
          )
        )
      }

      stories.forEach { item ->
        customPref(item)
      }

      if (hidden.isNotEmpty()) {
        customPref(
          ExpandHeader.Model(
            title = DSLSettingsText.from(R.string.StoriesLandingFragment__hidden_stories),
            isExpanded = state.isHiddenContentVisible,
            onClick = { viewModel.setHiddenContentVisible(it) }
          )
        )
      }

      if (state.isHiddenContentVisible) {
        hidden.forEach { item ->
          customPref(item)
        }
      }
    }
  }

  private fun createStoryLandingItem(data: StoriesLandingItemData): StoriesLandingItem.Model {
    return StoriesLandingItem.Model(
      data = data,
      onRowClick = { model, preview ->
        openStoryViewer(model, preview, false)
      },
      onForwardStory = {
        MultiselectForwardFragmentArgs.create(requireContext(), it.data.primaryStory.multiselectCollection.toSet()) { args ->
          MultiselectForwardFragment.showBottomSheet(childFragmentManager, args)
        }
      },
      onGoToChat = { model ->
        lifecycleDisposable += ConversationIntents.createBuilder(requireContext(), model.data.storyRecipient.id, -1L)
          .subscribeBy {
            startActivityIfAble(it.build())
          }
      },
      onHideStory = {
        if (!it.data.isHidden) {
          handleHideStory(it)
        } else {
          lifecycleDisposable += viewModel.setHideStory(it.data.storyRecipient, !it.data.isHidden).subscribe()
        }
      },
      onShareStory = {
        StoryContextMenu.share(this@StoriesLandingFragment, it.data.primaryStory.messageRecord as MmsMessageRecord)
      },
      onSave = {
        lifecycleScope.launch {
          StoryContextMenu.save(
            fragment = this@StoriesLandingFragment,
            messageRecord = it.data.primaryStory.messageRecord
          )
        }
      },
      onDeleteStory = {
        handleDeleteStory(it)
      },
      onInfo = { model, preview ->
        openStoryViewer(model, preview, true)
      },
      onAvatarClick = {
        cameraFab.performClick()
      },
      onLockList = {
        recyclerView?.suppressLayout(true)
      },
      onUnlockList = {
        recyclerView?.suppressLayout(false)
      }
    )
  }

  private fun openStoryViewer(model: StoriesLandingItem.Model, preview: View, isFromInfoContextMenuAction: Boolean) {
    if (model.data.storyRecipient.isMyStory) {
      startActivityIfAble(Intent(requireContext(), MyStoriesActivity::class.java))
    } else if (model.data.primaryStory.messageRecord.isOutgoing && model.data.primaryStory.messageRecord.isFailed) {
      if (model.data.primaryStory.messageRecord.isIdentityMismatchFailure) {
        SafetyNumberBottomSheet
          .forMessageRecord(requireContext(), model.data.primaryStory.messageRecord)
          .show(childFragmentManager)
      } else {
        StoryDialogs.resendStory(requireContext()) {
          lifecycleDisposable += viewModel.resend(model.data.primaryStory.messageRecord).subscribe()
        }
      }
    } else {
      val options = ActivityOptionsCompat.makeSceneTransitionAnimation(requireActivity(), preview, ViewCompat.getTransitionName(preview) ?: "")

      val record = model.data.primaryStory.messageRecord as MmsMessageRecord
      val blur = record.slideDeck.thumbnailSlide?.placeholderBlur
      val (text: StoryTextPostModel?, image: Uri?) = if (record.storyType.isTextStory) {
        StoryTextPostModel.parseFrom(record) to null
      } else {
        null to record.slideDeck.thumbnailSlide?.uri
      }

      startActivityIfAble(
        StoryViewerActivity.createIntent(
          context = requireContext(),
          storyViewerArgs = StoryViewerArgs(
            recipientId = model.data.storyRecipient.id,
            storyId = -1L,
            isInHiddenStoryMode = model.data.isHidden,
            storyThumbTextModel = text,
            storyThumbUri = image,
            storyThumbBlur = blur,
            recipientIds = viewModel.getRecipientIds(model.data.isHidden, model.data.storyViewState == StoryViewState.UNVIEWED),
            isFromInfoContextMenuAction = isFromInfoContextMenuAction,
            isJumpToUnviewed = model.data.storyViewState == StoryViewState.UNVIEWED
          )
        ),
        options.toBundle()
      )
    }
  }

  private fun handleDeleteStory(model: StoriesLandingItem.Model) {
    lifecycleDisposable += StoryContextMenu.delete(requireContext(), setOf(model.data.primaryStory.messageRecord)).subscribe()
  }

  private fun handleHideStory(model: StoriesLandingItem.Model) {
    StoryDialogs.hideStory(requireContext(), model.data.storyRecipient.getShortDisplayName(requireContext())) {
      viewModel.setHideStory(model.data.storyRecipient, true).subscribe {
        Snackbar.make(cameraFab, R.string.StoriesLandingFragment__story_hidden, Snackbar.LENGTH_SHORT)
          .show()
      }
    }
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  private fun startActivityIfAble(intent: Intent, options: Bundle? = null) {
    if (viewModel.isTransitioningToAnotherScreen) {
      return
    }

    viewModel.isTransitioningToAnotherScreen = true
    startActivity(intent, options)
  }

  private fun isSearchOpen(): Boolean {
    return isSearchVisible()
  }

  private fun isSearchVisible(): Boolean {
    return mainToolbarViewModel.state.value.mode == MainToolbarMode.SEARCH
  }

  private fun closeSearchIfOpen(): Boolean {
    if (isSearchOpen()) {
      mainToolbarViewModel.setToolbarMode(MainToolbarMode.FULL)
      return true
    }
    return false
  }
}

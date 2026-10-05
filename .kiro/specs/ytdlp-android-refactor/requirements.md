# Requirements Document

## Introduction

This document captures the requirements for a comprehensive architectural refactoring and modernization of the yt-dlp Android application. The app is a native Android frontend for yt-dlp using Chaquopy Python integration, with download management, audio playback, library management, and playlist functionality.

The current codebase has a 4,073-line monolithic `MainActivity` that mixes UI rendering, business logic, database operations, and service communication. This refactoring addresses architecture, performance, UI/UX, code quality, and dependency injection across five phases while preserving all existing user-facing functionality.

## Glossary

- **MainActivity**: The single Android `Activity` class currently containing all UI and business logic (~4,073 lines).
- **ViewModel**: An Android Architecture Component class that holds and manages UI-related state, surviving configuration changes.
- **Repository**: A data-access layer class that abstracts the source (database, network, filesystem) from the rest of the app.
- **MVVM**: Model-View-ViewModel architectural pattern; the target architecture for this refactoring.
- **Hilt**: Google's Android dependency injection library built on top of Dagger.
- **Coroutine**: A Kotlin concurrency primitive for writing asynchronous code sequentially.
- **StateFlow**: A Kotlin coroutines type that emits current state and subsequent updates to its collectors.
- **RecyclerView**: An Android widget for efficiently displaying large lists using view recycling.
- **DiffUtil**: An Android utility that computes the difference between two lists and produces a minimal update set for `RecyclerView`.
- **Coil**: A Kotlin-first Android image loading library with lifecycle awareness and coroutine support.
- **Glide**: A widely-used Android image loading and caching library.
- **detekt**: A static code analysis tool for Kotlin.
- **ktlint**: A Kotlin linter and code formatter.
- **Timber**: A logging utility for Android that wraps `android.util.Log`.
- **DownloadService**: The existing Android `Service` class managing yt-dlp download jobs in the background.
- **PlaybackService**: The existing Android `Service` class managing audio playback via `MediaPlayer`.
- **PlaylistStore**: The existing SQLite-backed store for playlists, track metadata, and playback history.
- **DownloadQueueStore**: The existing SQLite-backed store for download job state.
- **ANR**: Application Not Responding; an Android error triggered when the main thread is blocked for more than 5 seconds.
- **DI**: Dependency Injection.
- **KDoc**: Kotlin's documentation comment format, equivalent to Javadoc.
- **SearchViewModel**: The ViewModel responsible for music search state and operations.
- **LibraryViewModel**: The ViewModel responsible for the local audio library state, filtering, and sorting.
- **PlaylistViewModel**: The ViewModel responsible for playlist CRUD and track management state.
- **PlayerViewModel**: The ViewModel responsible for playback state and playback control commands.
- **DownloadViewModel**: The ViewModel responsible for download queue state and settings.
- **TrackRepository**: The Repository that abstracts MediaStore and metadata access.
- **PlaylistRepository**: The Repository that abstracts `PlaylistStore` access.
- **DownloadRepository**: The Repository that abstracts `DownloadQueueStore` access.
- **UI Component**: A self-contained Kotlin file or composable responsible for rendering a single screen or section.
- **Result_Type**: Kotlin's `Result<T>` sealed type representing success or failure.
- **Skeleton_Loader**: A placeholder UI pattern that shows the shape of content while data loads.

---

## Requirements

### Requirement 1: MVVM ViewModel Extraction

**User Story:** As a developer, I want business logic and UI state extracted into dedicated ViewModels, so that `MainActivity` becomes a thin orchestrator and state survives configuration changes.

#### Acceptance Criteria

1. THE `SearchViewModel` SHALL manage the online music search query, paginated result list, per-URL download indicator states, and loading/error state as `StateFlow` properties.
2. THE `LibraryViewModel` SHALL manage the full audio track list, filtered and sorted track list, search query, group mode, sort mode, loading state, and error state as `StateFlow` properties.
3. THE `PlaylistViewModel` SHALL manage the list of saved playlists, the active playlist detail, loading state, and error state as `StateFlow` properties.
4. THE `PlayerViewModel` SHALL manage the current `PlaybackSnapshot`, playback commands (play, pause, next, previous, seek), and sleep timer state as `StateFlow` properties.
5. THE `DownloadViewModel` SHALL manage the download job list, format mode, playlist toggle, concurrency setting, Wi-Fi–only setting, loading state, and error state as `StateFlow` properties.
6. WHEN a ViewModel emits a new state, THE `MainActivity` SHALL update only the UI section bound to that ViewModel's `StateFlow`, leaving all other UI sections unchanged.
7. THE `MainActivity` SHALL contain no direct database queries, no direct filesystem reads, and no direct `PlaylistStore` or `DownloadQueueStore` calls after the refactoring.
8. WHEN the device is rotated or the `Activity` is recreated, THE ViewModel SHALL emit the same value it held before the configuration change, with no new request issued to the data layer.
9. IF a ViewModel operation fails, THEN THE ViewModel SHALL set a non-null, non-empty string on its error `StateFlow` describing the failure, and SHALL NOT crash.
10. THE `SearchViewModel` SHALL support search pagination; WHEN the user scrolls within 5 items of the last loaded result, THE `SearchViewModel` SHALL append the next page of results to the existing list without clearing it.

---

### Requirement 2: Repository Pattern Implementation

**User Story:** As a developer, I want all data access centralized in Repository classes, so that ViewModels never depend on Android-specific storage APIs directly.

#### Acceptance Criteria

1. THE `TrackRepository` SHALL provide a suspend function that loads all audio tracks from `MediaStore` and returns a `List<AudioTrack>`.
2. THE `TrackRepository` SHALL provide a suspend function that extracts metadata from a media `Uri` and returns `null` when no `MediaStore` entry exists for that `Uri`; it SHALL throw on parse or I/O errors.
3. THE `PlaylistRepository` SHALL provide suspend functions for all `PlaylistStore` operations: get playlists, create playlist, delete playlist, add track, remove track, move track, get track metadata, save track metadata, record playback, get playback history, delete history entry, and clear history.
4. THE `DownloadRepository` SHALL provide suspend functions for all `DownloadQueueStore` operations: enqueue, claim next queued, get jobs, update job, move job to front, clear finished, cancel, retry, resume, resume network-paused, recover interrupted, has queued, has network-paused, count queued, and count paused.
5. WHEN a Repository method is called, THE Repository SHALL execute the operation using `withContext(Dispatchers.IO)` so that it is safe to call from any coroutine dispatcher without blocking the caller's thread.
6. THE `TrackRepository` SHALL NOT hold a direct reference to `MainActivity` or any `Activity` context; it SHALL use `@ApplicationContext`.
7. THE `PlaylistRepository` SHALL NOT hold a direct reference to `MainActivity` or any `Activity` context; it SHALL use `@ApplicationContext`.
8. THE `DownloadRepository` SHALL NOT hold a direct reference to `MainActivity` or any `Activity` context; it SHALL use `@ApplicationContext`.
9. THE `TrackRepository` SHALL expose a `StateFlow<Boolean>` loading property that is set to `true` before a track-load begins and `false` when it completes or throws.
10. IF any Repository operation throws an exception, THEN THE Repository SHALL propagate the exception to the caller rather than silently swallowing it, so the ViewModel can handle it.

---

### Requirement 3: Dependency Injection with Hilt

**User Story:** As a developer, I want Hilt to manage object creation and lifetimes, so that ViewModels, Repositories, and Services receive their dependencies automatically and the app is easier to test.

#### Acceptance Criteria

1. THE `YtdlpApplication` class SHALL be annotated with `@HiltAndroidApp` to initialize Hilt at application startup.
2. THE `MainActivity` SHALL be annotated with `@AndroidEntryPoint` and SHALL receive its ViewModels via `@HiltViewModel` injection.
3. THE `DownloadService` SHALL be annotated with `@AndroidEntryPoint` and SHALL declare all its dependencies as constructor-injected parameters with no manual instantiation inside the class body.
4. THE `PlaybackService` SHALL be annotated with `@AndroidEntryPoint` and SHALL declare all its dependencies as constructor-injected parameters with no manual instantiation inside the class body.
5. THE `TrackRepository`, `PlaylistRepository`, and `DownloadRepository` SHALL each be annotated with `@Singleton` and provided via a Hilt module.
6. THE `@ApplicationContext` SHALL be injected into Repositories via Hilt rather than passed manually.
7. WHEN a `@HiltViewModel`-annotated ViewModel is requested by an `Activity` or `Fragment`, THE Hilt framework SHALL provide the ViewModel with all its declared constructor dependencies.
8. THE Hilt setup SHALL NOT introduce compilation failures in existing unit tests that do not use Hilt; new Hilt-dependent tests SHALL use `@HiltAndroidTest` or substitute fakes via `@TestInstallIn`.
9. THE Hilt module that provides Repositories SHALL compile without error and all provided bindings SHALL be resolvable at compile time.

---

### Requirement 4: Asynchronous Operations and Main-Thread Safety

**User Story:** As a developer, I want all blocking I/O and database operations moved off the main thread using coroutines, so that the app never triggers ANRs and the UI remains responsive.

#### Acceptance Criteria

1. THE app SHALL perform zero database read or write operations on the Android main thread.
2. THE app SHALL perform zero filesystem read or write operations (metadata extraction, artwork loading, file copy) on the Android main thread.
3. WHEN a ViewModel launches a coroutine, THE ViewModel SHALL use `viewModelScope` so the coroutine is cancelled automatically when the ViewModel is cleared.
4. WHEN a Repository method performs I/O or database access, THE Repository SHALL execute on a background dispatcher and SHALL be safe to call from any coroutine dispatcher.
5. WHEN a coroutine encounters an exception, THE ViewModel SHALL catch the exception and set a non-null error value on its error `StateFlow` rather than letting the exception propagate uncaught to `viewModelScope`.
6. THE `PlaylistStore` operations previously called from `MainActivity` (e.g., `toggleTrackLike`, `choosePlaylistForTrack`) SHALL be migrated so that `MainActivity` invokes ViewModel functions, which in turn call Repository suspend functions on a background dispatcher.
7. THE `refreshLibrary` operation SHALL run fully on a background dispatcher and SHALL post its results to the main thread only when complete.
8. THE `refreshDownloadedFiles` operation SHALL run fully on a background dispatcher and SHALL post its results to the main thread only when complete.
9. WHEN an I/O or database operation is in progress, THE ViewModel SHALL set its loading `StateFlow<Boolean>` to `true` before the operation begins and `false` when it completes or fails.
10. IF `refreshLibrary` or `refreshDownloadedFiles` fails, THEN THE ViewModel SHALL set a non-null error value on its error `StateFlow` and SHALL leave the previously loaded data unchanged.

---

### Requirement 5: RecyclerView Migration for Lists

**User Story:** As a developer, I want all large lists migrated from `GridLayout` to `RecyclerView` with `DiffUtil`, so that list rendering is efficient and UI frame rate is maintained during updates.

#### Acceptance Criteria

1. THE music library track list SHALL be rendered using a `RecyclerView` with a `ListAdapter` backed by `DiffUtil.ItemCallback<AudioTrack>`.
2. THE download queue list SHALL be rendered using a `RecyclerView` with a `ListAdapter` backed by `DiffUtil.ItemCallback<DownloadJob>`.
3. THE search results grid SHALL be rendered using a `RecyclerView` with a `ListAdapter` backed by `DiffUtil.ItemCallback<MusicSearchResult>`.
4. THE playlist list SHALL be rendered using a `RecyclerView` with a `ListAdapter` backed by `DiffUtil.ItemCallback<SavedPlaylist>`.
5. THE recent history grid SHALL be rendered using a `RecyclerView` with a `ListAdapter` backed by `DiffUtil.ItemCallback<PlaybackHistoryEntry>`.
6. THE download history grid SHALL be rendered using a `RecyclerView` with a `ListAdapter` backed by `DiffUtil.ItemCallback<DownloadedTrack>`.
7. WHEN any field of a list item changes, THE `RecyclerView` SHALL rebind only the affected item(s) via `notifyItemChanged` with a payload, and SHALL NOT remove and re-add all views.
8. THE `DiffUtil.ItemCallback` for `AudioTrack` SHALL use URI as the unique item identity and SHALL compare title, artist, duration, and URI for content equality.
9. THE `DiffUtil.ItemCallback` for `DownloadJob` SHALL use job ID as the unique item identity and SHALL compare job status, progress percentage, and error message for content equality.
10. WHEN a new track list is submitted to the library `ListAdapter`, THE `RecyclerView` SHALL animate additions, removals, and moves using the default `DefaultItemAnimator`.
11. THE `RecyclerView` adapters SHALL NOT hold an `Activity` instance as a field; state observation SHALL be bound to `ViewLifecycleOwner`.
12. THE `DiffUtil.ItemCallback` for `MusicSearchResult` SHALL use URL as unique identity; for `SavedPlaylist` SHALL use playlist ID; for `PlaybackHistoryEntry` SHALL use entry timestamp; for `DownloadedTrack` SHALL use file URI.

---

### Requirement 6: Image Loading with Coil

**User Story:** As a developer, I want all thumbnail and artwork loading delegated to Coil, so that image fetching is lifecycle-aware, memory is managed automatically, and the thumbnail executor pool is eliminated.

#### Acceptance Criteria

1. THE app SHALL use Coil as the sole image loading library for all thumbnail and artwork `ImageView` targets.
2. WHEN an `ImageView` is detached from the window, Coil SHALL cancel any in-progress load for that `ImageView`.
3. WHEN the lifecycle owner associated with an `ImageView` reaches the `DESTROYED` state, Coil SHALL cancel any in-progress or pending load for that `ImageView`.
4. THE manual `thumbnailExecutor` thread pool in `MainActivity` SHALL be removed and replaced by Coil's internal dispatcher.
5. THE manual `loadThumbnail` method in `MainActivity` SHALL be deleted; all call sites SHALL use Coil's `load` extension function.
6. WHEN a thumbnail URL fails to load, Coil SHALL display the placeholder drawable (`android.R.drawable.ic_media_play`) without propagating an exception to the caller.
7. IF a URL does not begin with `https://i.ytimg.com/`, THEN it SHALL NOT be passed to Coil; the placeholder drawable SHALL be shown instead.
8. THE Coil `ImageLoader` SHALL be configured with a maximum memory cache size of no more than 25% of the runtime maximum heap size.
9. WHEN the same thumbnail URL is requested multiple times, Coil SHALL serve subsequent requests from its in-memory or disk cache rather than making new network requests.
10. IF a thumbnail response body exceeds 5 MB, THEN the load SHALL be cancelled and the placeholder drawable SHALL be displayed.

---

### Requirement 7: Search Result Download Indicator Optimization

**User Story:** As a developer, I want the search result download indicator update logic optimized from O(n×m) to O(n), so that UI updates during active downloads do not degrade with queue size.

#### Acceptance Criteria

1. WHEN `renderSearchDownloadIndicators` executes, THE `SearchViewModel` SHALL derive a URL-to-download-status map from the current job list in a single pass over jobs before any indicator is evaluated, where download status is one of: `DOWNLOADING`, `QUEUED`, or `NONE`.
2. WHEN the download queue state changes, THE `SearchViewModel` SHALL emit an updated URL-to-download-status map so that `MainActivity` consumes the pre-computed map directly without iterating over the job list.
3. WHEN `renderSearchDownloadIndicators` iterates over search result indicators, THE function SHALL perform at most one map lookup per indicator URL and SHALL NOT iterate over the job list for any individual indicator.
4. THE `SearchViewModel` SHALL expose the URL-to-download-status map as an observable `StateFlow` such that `MainActivity` does not contain any inline loop or query over the raw `DownloadJob` list to determine indicator states.
5. FOR each search result URL, THE optimized `renderSearchDownloadIndicators` SHALL assign the same `DOWNLOADING`, `QUEUED`, or `NONE` indicator state as the original implementation when the URL appears at most once in the job list.

---

### Requirement 8: XML Layout Migration for UI Screens

**User Story:** As a developer, I want each application screen defined in an XML layout file rather than built programmatically, so that layouts can be previewed in Android Studio and are easier to maintain.

#### Acceptance Criteria

1. THE Home screen layout SHALL be defined in a dedicated XML layout file (`activity_main.xml` or a fragment/custom view equivalent), with no view hierarchy constructed programmatically outside of that file.
2. THE Search screen layout SHALL be defined in a dedicated XML layout file, with no view hierarchy constructed programmatically outside of that file.
3. THE Download Queue screen layout SHALL be defined in a dedicated XML layout file, with no view hierarchy constructed programmatically outside of that file.
4. THE Library screen layout SHALL be defined in a dedicated XML layout file, with no view hierarchy constructed programmatically outside of that file.
5. THE Playlists screen layout SHALL be defined in a dedicated XML layout file, with no view hierarchy constructed programmatically outside of that file.
6. THE Player screen layout SHALL be defined in a dedicated XML layout file, with no view hierarchy constructed programmatically outside of that file.
7. THE list item views for tracks, download jobs, search results, and playlists SHALL each be defined in a dedicated XML item layout file, resulting in exactly 4 item layout files.
8. WHILE each screen is displayed, THE system SHALL render all views using the attributes defined in the corresponding XML layout file, such that each view's background color, foreground color, padding, margin, corner radius, and text size match the values previously set programmatically.
9. THE existing color constants (e.g., `COLOR_BACKGROUND`, `COLOR_ACCENT`) SHALL be migrated to `res/values/colors.xml` and referenced via `@color` resources in all layout files that previously used those constants.
10. THE existing dimension constants (e.g., padding, corner radii) SHALL be migrated to `res/values/dimens.xml` and referenced via `@dimen` resources in all layout files that previously used those constants.
11. IF the programmatic code contains `dp()` conversion helper calls, THEN THE system SHALL replace those calls with the corresponding `dp` unit values defined in `res/values/dimens.xml`, and the `dp()` helper SHALL no longer be called from layout-related code.

---

### Requirement 9: Loading and Empty States

**User Story:** As a developer, I want every screen that loads data asynchronously to show a loading indicator while loading and a contextual empty state when no data is available, so that users always understand the current state of the app.

#### Acceptance Criteria

1. WHILE the library is loading tracks, THE Library screen SHALL display a progress indicator in place of the track list.
2. WHEN the library finishes loading with zero tracks and no error, THE Library screen SHALL display an empty state message with a call-to-action button to import audio.
3. WHILE search results are loading, THE Search screen SHALL display a loading indicator in place of the results grid.
4. WHEN a search returns zero results, THE Search screen SHALL display an empty state message that includes the submitted query text.
5. WHILE the download queue is loading, THE Queue screen SHALL display a progress indicator in place of the job list.
6. WHEN the download queue has no jobs, THE Queue screen SHALL display an empty state message indicating no active or pending downloads.
7. WHILE playlists are loading, THE Playlists screen SHALL display a progress indicator in place of the playlist list.
8. WHEN the playlists list is empty, THE Playlists screen SHALL display an empty state message with a call-to-action to create a playlist.
9. WHILE playback history is loading, THE Home and Library recent-history grids SHALL display a loading indicator in place of the history content.
10. WHEN a data-fetch operation fails for any reason, THE affected screen SHALL display an error state message and a retry button in place of the current content area.
11. WHEN the user activates the retry button, THE system SHALL re-trigger the same data-fetch operation that originally failed.
12. IF any screen's data-fetch operation fails, THEN THE screen SHALL display the error message inline within the content area rather than as a Toast.

---

### Requirement 10: Error Handling with Snackbar and Result Type

**User Story:** As a developer, I want errors shown via Snackbar with optional retry actions instead of Toasts, and all fallible operations modeled with `Result<T>`, so that users can act on errors and the codebase has a consistent error contract.

#### Acceptance Criteria

1. THE app SHALL use `Snackbar` instead of `Toast` for all user-facing error messages.
2. WHEN a recoverable operation fails (i.e., an operation for which the ViewModel exposes a retry action, such as a failed download or a failed playlist track add), THE Snackbar SHALL include an action button labeled with the applicable retry or undo label and SHALL display for `Snackbar.LENGTH_LONG` duration.
3. WHEN a non-recoverable error is shown (i.e., any failure for which no retry action is defined in the ViewModel), THE Snackbar SHALL display for `Snackbar.LENGTH_LONG` duration with no action button.
4. THE `showError` helper method in `MainActivity` SHALL be replaced by a helper that shows a `Snackbar` anchored to the root content view of the current screen.
5. ALL ViewModel functions that perform fallible operations SHALL return `Unit` and communicate failure via a `StateFlow<String?>`, where a non-null value represents an active error message; they SHALL NOT propagate exceptions to the caller.
6. IF a `TrackRepository` function throws an exception, THEN THE calling ViewModel SHALL catch the exception and map it to a non-null error value on its error `StateFlow`.
7. IF a `PlaylistRepository` function throws an exception, THEN THE calling ViewModel SHALL catch the exception and map it to a non-null error value on its error `StateFlow`.
8. IF a `DownloadRepository` function throws an exception, THEN THE calling ViewModel SHALL catch the exception and map it to a non-null error value on its error `StateFlow`.
9. WHEN THE `SearchViewModel` receives a search failure, THE `SearchViewModel` SHALL set a non-null value on its error `StateFlow`.
10. WHEN the Search screen observes a non-null error value on the `SearchViewModel` error `StateFlow`, THE Search screen SHALL display the error message and a "Try again" button that re-triggers the last search.
11. THE existing `AlertDialog`-based confirmation flows (delete playlist, delete download, clear history) SHALL be preserved; only Toast-based error reporting is replaced.

---

### Requirement 11: Dark Mode and Theme Support

**User Story:** As a developer, I want all colors and dimensions to use theme attributes so that the app can support system dark mode and per-theme overrides without code changes.

#### Acceptance Criteria

1. ALL hardcoded color integers in `MainActivity` and all Kotlin source files SHALL be replaced with references to named color resources defined in `res/values/colors.xml`.
2. THE app SHALL define a light theme and a dark theme in `res/values/themes.xml` and `res/values-night/themes.xml` respectively.
3. WHEN the system switches to dark mode, THE app SHALL display the dark theme color palette without requiring an app restart.
4. THE `AndroidManifest.xml` SHALL declare `android:theme` pointing to the app's base theme rather than hardcoding colors.
5. THE dark theme SHALL preserve the existing dark-first color palette as defined by the named color resources migrated in criterion 1.
6. THE light theme SHALL define color resources with contrast ratios meeting WCAG AA (4.5:1 for normal text, 3:1 for large text).
7. THE `res/values/colors.xml` SHALL contain only named color values; layout XML files SHALL NOT contain hardcoded color integers and SHALL reference only `@color` resources or theme attributes.

---

### Requirement 12: Accessibility Improvements

**User Story:** As a developer, I want all interactive elements to have accurate content descriptions and meet minimum touch target sizes, so that users relying on assistive technologies can navigate the app.

#### Acceptance Criteria

1. ALL `ImageView`, `Button`, and custom interactive views that use Unicode glyphs (↓, ⋮, ✓, ×, ▶, Ⅱ) as their only visible label SHALL have a `contentDescription` attribute set.
2. WHERE a `Button` uses a glyph as text, THE button's `contentDescription` SHALL describe the action (e.g., "Download", "More options", "Pause") rather than repeat the glyph.
3. ALL interactive views SHALL have a minimum touch target size of 48dp × 48dp.
4. WHEN the download state of a search result changes, THE `contentDescription` of the indicator button SHALL be updated programmatically to reflect the new state (e.g., "Downloading", "Download complete", "Download").
5. THE `RecyclerView` list items for audio tracks SHALL include a `contentDescription` on the item root that combines track title and artist in the format "Track title by Artist name".
6. WHILE a track is playing, THE player artwork `ImageView` SHALL have a `contentDescription` that states the currently playing track title and artist.
7. THE now-playing bar root view SHALL have a `contentDescription` that states the currently playing track title and artist, readable by TalkBack as a single announcement without requiring navigation into child views.
8. ALL `CheckBox` controls (Wi-Fi only, playlist allow, equalizer toggle) SHALL have an associated label such that TalkBack announces both the label text and the checked/unchecked state when the checkbox receives focus.
9. WHEN no track is playing, THE player artwork `ImageView` SHALL have a `contentDescription` of "No track playing".

---

### Requirement 13: Kotlin Unit Test Coverage

**User Story:** As a developer, I want unit tests for all ViewModels and Repositories so that regressions are caught automatically and the codebase has a measurable quality baseline.

#### Acceptance Criteria

1. THE `SearchViewModel` SHALL have unit tests that: assert `searchResults` is empty on initialization; assert `searchResults` equals the repository-returned list after a successful search; assert `errorState` is non-null after a repository-thrown exception; assert pagination appends results to the existing list rather than replacing it; and assert the URL-to-download-status map updates when the job list changes.
2. THE `LibraryViewModel` SHALL have unit tests that: assert `tracks` equals the repository-returned list after load; assert `filteredTracks` contains only items matching the search query; assert `filteredTracks` order changes correctly for each sort mode; assert `groupedTracks` keys match the expected group values for each group mode; and assert `errorState` is non-null after a repository-thrown exception.
3. THE `PlaylistViewModel` SHALL have unit tests that: assert `playlists` equals the repository-returned list after load; assert `playlists` count increases by one after `createPlaylist`; assert `playlists` no longer contains a playlist after `deletePlaylist`; assert the affected playlist's track count changes after `addTrack`; and assert `errorState` is non-null after a repository-thrown exception.
4. THE `PlayerViewModel` SHALL have unit tests that: assert `playbackSnapshot` reflects the value set via `setPlaybackSnapshot`; assert the play command calls the service exactly once; assert the seek command calls the service with the exact position argument; and assert no crash occurs when `setPlaybackService(null)` is called followed by a play command.
5. THE `DownloadViewModel` SHALL have unit tests that: assert `jobs` equals the repository-returned list after load; assert `enqueueUrls` calls the repository exactly once with the provided URL list; assert concurrency values less than 1 are clamped to 1; assert concurrency values greater than 10 are clamped to 10; assert the Wi-Fi–only toggle propagates the new value to the service; and assert `jobs` no longer contains finished jobs after `clearFinished`.
6. THE `TrackRepository` SHALL have unit tests that: assert `loadLibraryTracks` returns the list produced by a fake `ContentResolver`; and assert `extractMetadata` returns `null` when the retriever throws.
7. THE `PlaylistRepository` SHALL have unit tests that: assert `getPlaylists` delegates to `PlaylistStore` exactly once; assert `createPlaylist` returns the ID produced by `PlaylistStore`; and assert `saveTrackMetadata` calls `PlaylistStore.saveTrackMetadata` with the same arguments.
8. THE `DownloadRepository` SHALL have unit tests that: assert `enqueue` calls `DownloadQueueStore.enqueue` exactly once; assert `cancelJob` returns the boolean returned by the store; and assert `getJobs` returns the list returned by the store.
9. ALL ViewModel tests SHALL use `kotlinx-coroutines-test` with `StandardTestDispatcher` injected via `Dispatchers.setMain()` in `@Before` and reset via `Dispatchers.resetMain()` in `@After`, and SHALL NOT depend on real `Dispatchers.IO` or `Dispatchers.Main`.
10. THE Gradle build SHALL include a coverage verification task (e.g., `koverVerify`) that fails the build if line coverage across all ViewModel and Repository classes drops below 70%.
11. THE `LibraryViewModel` filter function SHALL have a parameterised test with at least 5 distinct `AudioTrack` lists asserting that applying the filter twice with the same query produces the same result as applying it once.
12. THE `LibraryViewModel` sort function SHALL have a parameterised test with at least 5 distinct lists asserting that sorting an already-sorted list for the same sort mode produces an identical order.

---

### Requirement 14: Static Analysis and Code Formatting

**User Story:** As a developer, I want detekt and ktlint configured in the Gradle build so that code style and quality issues are caught at build time.

#### Acceptance Criteria

1. THE `build.gradle.kts` (or a dedicated Gradle module config) SHALL include the `detekt` plugin with a `detekt.yml` configuration file committed to the repository.
2. THE `build.gradle.kts` SHALL include the `ktlint` plugin (via `jlleitschuh/ktlint-gradle` or equivalent) configured to run on all Kotlin source sets.
3. WHEN `./gradlew detekt` is executed, THE build SHALL fail if any detekt rule with severity `error` is violated, and SHALL succeed if no such rules are violated.
4. WHEN `./gradlew ktlintCheck` is executed, THE build SHALL fail if any `.kt` file in the project's Kotlin source sets violates the configured style rules, and SHALL succeed if no violations are found.
5. THE `detekt.yml` SHALL enable at minimum the following rule sets: `complexity`, `naming`, `style`, and `potential-bugs`.
6. THE `detekt.yml` SHALL configure `complexity > LongMethod` with a threshold of 40 non-blank, non-comment lines and `complexity > LargeClass` with a threshold of 400 lines.
7. THE CI-equivalent Gradle check task SHALL include `detekt` and `ktlintCheck` as dependencies so they run with `./gradlew check`.
8. THE `ktlint` plugin SHALL be configured with `officialCodeStyle = true` to use the official Kotlin coding conventions.
9. WHEN `python -m pytest tests/test_downloader.py` is executed after the Gradle configuration changes, THE command SHALL exit with code 0 and produce the same number of passing tests as before the changes.

---

### Requirement 15: KDoc Documentation

**User Story:** As a developer, I want all public classes, functions, and properties in the refactored codebase documented with KDoc, so that new contributors can understand the purpose and contract of each component.

#### Acceptance Criteria

1. EVERY public class in the ViewModel layer SHALL have a KDoc comment describing its responsibility.
2. EVERY public function in the Repository layer SHALL have a KDoc comment with `@param` tags for each parameter, a `@return` tag describing the return value, and a `@throws` tag for each exception the function may throw.
3. EVERY public `StateFlow` property on a ViewModel SHALL have a KDoc comment describing what state it represents and the conditions under which its value changes.
4. THE `DownloadService` public API (all public functions and the `Listener` interface) SHALL have KDoc comments on each member.
5. THE `PlaybackService` public API (all public functions and the `Listener` interface) SHALL have KDoc comments on each member.
6. WHEN a function's behavior depends on a precondition indicated by a parameter being nullable, a required prior call, or an enforced value range, THE KDoc SHALL document that precondition with a `@throws` or descriptive `@param` note.
7. THE `DownloadJob`, `PlaybackSnapshot`, `AudioTrack`, and `MusicSearchResult` data classes SHALL have KDoc comments on each property explaining its semantics.

---

### Requirement 16: Consistent Logging with Timber

**User Story:** As a developer, I want all log statements to use Timber so that debug logs are stripped from release builds automatically and log tags are consistent.

#### Acceptance Criteria

1. THE `app/build.gradle.kts` SHALL declare `com.jakewharton.timber:timber:5.0.1` as a dependency.
2. THE `YtdlpApplication.onCreate()` SHALL plant a `Timber.DebugTree()` when `BuildConfig.DEBUG` is `true` and SHALL NOT plant any tree in release builds, resulting in zero log output in release by default.
3. ALL `android.util.Log.v`, `android.util.Log.d`, `android.util.Log.i`, `android.util.Log.w`, and `android.util.Log.e` calls in Kotlin source files SHALL be replaced with the corresponding `Timber.v`, `Timber.d`, `Timber.i`, `Timber.w`, and `Timber.e` calls.
4. WHEN an exception is logged, THE Timber call SHALL pass the `Throwable` as the first argument so the full stack trace is captured.
5. THE Python `downloader.py` logging configuration SHALL remain unchanged; this requirement applies only to Kotlin source files.

---

### Requirement 17: Behavioral Equivalence Preservation

**User Story:** As a developer, I want all refactoring changes to preserve existing user-facing behavior so that no features are accidentally removed or broken during the modernization.

#### Acceptance Criteria

1. THE refactored app SHALL support all download formats available before refactoring: `best`, `1080p`, `720p`, `480p`, `audio`, `audio_mp3`.
2. THE refactored app SHALL support download queue operations: enqueue, cancel, pause, resume, retry, move to front, and clear finished.
3. WHEN the device transitions from a non-Wi-Fi network to a Wi-Fi network while the Wi-Fi–only policy is enabled, THE app SHALL automatically resume all network-paused jobs; WHEN Wi-Fi is lost, THE app SHALL automatically pause all active jobs.
4. THE refactored app SHALL support audio playback with shuffle, repeat (off/all/one), playback speed control in the range 0.5×–2.0×, equalizer toggle, sleep timer, and seek.
5. THE refactored app SHALL support library browsing with search, sort (title/artist/album/date/duration), and group (none/artist/album) modes.
6. THE refactored app SHALL support playlist creation, deletion, track addition, track removal, track reordering, M3U import, and M3U export (share).
7. THE refactored app SHALL support playback history recording, paginated history display, history entry deletion, and full history clear.
8. THE refactored app SHALL support music search via yt-dlp with paginated results and per-result download initiation.
9. THE refactored app SHALL support audio file import via `ACTION_OPEN_DOCUMENT` with persistable URI permissions.
10. WHEN the app is upgraded from a pre-refactoring version, the existing `PlaylistStore` and `DownloadQueueStore` SQLite databases SHALL be readable: `getPlaylists()`, `getJobs()`, and `getPlaybackHistory()` SHALL each return the same data they returned on the previous version.
11. THE existing Python `downloader.py` module and its associated tests SHALL remain functionally unchanged by this refactoring.
12. THE compiled behavior of the URL scheme validation, trusted thumbnail URL check, and path traversal prevention controls SHALL be unchanged after refactoring.

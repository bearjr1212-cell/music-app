# Phase 1: Critical Architecture Refactoring - Progress Report

## Completed ✅

### 1. Hilt Dependency Injection Setup
- Added Hilt plugin to project build files
- Added Hilt dependencies (2.48)
- Added Coroutines dependencies
- Created `YtdlpApplication` class with `@HiltAndroidApp`
- Updated AndroidManifest to use the Application class

**Files Modified:**
- `build.gradle.kts` (root)
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`

**Files Created:**
- `app/src/main/java/io/github/ytdlpandroid/YtdlpApplication.kt`

### 2. Repository Layer
Created three repository classes that wrap existing data stores with coroutine support:

#### TrackRepository
- Wraps MediaStore operations
- Loads library tracks asynchronously
- Extracts metadata from media files
- Loads artwork from media files
- All operations use `Dispatchers.IO` to avoid blocking main thread

**File:** `app/src/main/java/io/github/ytdlpandroid/repository/TrackRepository.kt`

#### PlaylistRepository
- Wraps `PlaylistStore` operations
- All database operations converted to suspend functions
- Manages playlists, track metadata, and playback history
- Uses `Dispatchers.IO` for database operations

**File:** `app/src/main/java/io/github/ytdlpandroid/repository/PlaylistRepository.kt`

#### DownloadRepository
- Wraps `DownloadQueueStore` operations
- All queue operations converted to suspend functions
- Manages download jobs, state updates, and queue operations
- Uses `Dispatchers.IO` for database operations

**File:** `app/src/main/java/io/github/ytdlpandroid/repository/DownloadRepository.kt`

### 3. SearchViewModel
Extracted search functionality from MainActivity into a dedicated ViewModel:
- Manages search state (query, results, loading, errors)
- Handles pagination with `loadNextPage()`
- Integrates with DownloadService for queuing downloads
- Uses StateFlow for reactive state management
- Saves search result metadata via PlaylistRepository

**File:** `app/src/main/java/io/github/ytdlpandroid/viewmodel/SearchViewModel.kt`

---

## Remaining Work 🚧

### 4. LibraryViewModel (Not Started)
**To Extract from MainActivity:**
- Library track loading and filtering
- Group modes (artist, album, etc.)
- Sort modes
- Library search functionality
- Import local audio files
- Track details display

**Estimated Complexity:** HIGH (~800 lines of MainActivity code)

### 5. PlaylistViewModel (Not Started)
**To Extract from MainActivity:**
- Playlist creation, editing, deletion
- Track add/remove/reorder within playlists
- Playlist import/export (M3U)
- "Liked Songs" management
- Playlist menu actions

**Estimated Complexity:** MEDIUM (~600 lines of MainActivity code)

### 6. PlayerViewModel (Not Started)
**To Extract from MainActivity:**
- Playback control (play, pause, seek, next, previous)
- Shuffle and repeat modes
- Playback speed control
- Sleep timer
- Equalizer control
- Now playing bar updates
- Integration with PlaybackService

**Estimated Complexity:** HIGH (~1000 lines of MainActivity code)

### 7. DownloadViewModel (Not Started)
**To Extract from MainActivity:**
- Download queue management
- Format selection
- Concurrency settings
- Wi-Fi-only scheduling
- Job pause/resume/cancel
- Download history
- Integration with DownloadService

**Estimated Complexity:** MEDIUM (~500 lines of MainActivity code)

### 8. MainActivity Refactoring (Not Started)
**Tasks:**
- Replace Executors with Coroutines
- Inject ViewModels via Hilt
- Replace direct service calls with ViewModel methods
- Observe StateFlow instead of direct callbacks
- Remove ~3000 lines of business logic
- Keep only UI rendering code (~500-800 lines)

**Estimated Complexity:** VERY HIGH

### 9. Testing (Not Started)
**Tasks:**
- Write unit tests for Repositories
- Write unit tests for ViewModels
- Write UI tests for MainActivity
- Verify all functionality still works

---

## Current State Assessment

### What Works Now
- Hilt is configured and ready for dependency injection
- Repository layer provides coroutine-based data access
- SearchViewModel demonstrates the ViewModel pattern
- Build configuration includes all necessary dependencies

### What Doesn't Work Yet
- ViewModels are not integrated into MainActivity
- MainActivity still uses the old architecture
- Executors are still in use (not migrated to coroutines)
- No tests for new code

### Build Status
⚠️ **The project will not build successfully yet** because:
- MainActivity still references private data classes that may need to be made public
- The AudioTrack data class exists in both MainActivity and TrackRepository (needs consolidation)
- ViewModels are created but not used
- Some imports may be missing

---

## Next Steps Options

### Option A: Continue Full Refactoring (Recommended for production)
Complete all remaining ViewModels and fully refactor MainActivity. This will take significant time but results in a clean, maintainable architecture.

**Estimated Time:** 2-3 days of focused work

### Option B: Incremental Integration (Recommended for learning)
Integrate the SearchViewModel into MainActivity first as a proof-of-concept, then extract other ViewModels one at a time. This allows testing each step.

**Estimated Time:** 1-2 days for first integration, then ongoing

### Option C: Stop Here and Document
Leave the foundation in place (Repositories, Hilt setup) and document how to complete the refactoring. This provides the architecture blueprint without the full implementation.

**Estimated Time:** 1 hour for documentation

---

## Architecture Diagram (Proposed)

```
┌─────────────────────────────────────────────────────────┐
│                     MainActivity                        │
│              (UI Rendering Only, ~500 lines)            │
└──────────────┬──────────────────────────────────────────┘
               │ observes StateFlow
               ▼
┌─────────────────────────────────────────────────────────┐
│                   ViewModels                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ SearchViewModel│ │ LibraryViewModel││ PlayerViewModel│ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
│  ┌──────────────┐  ┌──────────────┐                   │
│  │ PlaylistViewModel│ │ DownloadViewModel│             │
│  └──────────────┘  └──────────────┘                   │
└──────────────┬──────────────────────────────────────────┘
               │ uses coroutines
               ▼
┌─────────────────────────────────────────────────────────┐
│                   Repositories                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ TrackRepository│ │ PlaylistRepository│ │ DownloadRepository│ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
└──────────────┬──────────────────────────────────────────┘
               │ Dispatchers.IO
               ▼
┌─────────────────────────────────────────────────────────┐
│                  Data Stores                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ MediaStore   │ │ PlaylistStore │ │ DownloadQueueStore│ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────┘
```

---

## Integration Example (How to use SearchViewModel in MainActivity)

```kotlin
@AndroidEntryPoint
class MainActivity : Activity() {
    
    @Inject
    lateinit var searchViewModel: SearchViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Observe search results
        lifecycleScope.launch {
            searchViewModel.searchResults.collect { results ->
                renderSearchResults(results)
            }
        }
        
        // Observe loading state
        lifecycleScope.launch {
            searchViewModel.isSearching.collect { isSearching ->
                searchButton.isEnabled = !isSearching
            }
        }
        
        // Observe errors
        lifecycleScope.launch {
            searchViewModel.searchError.collect { error ->
                error?.let { showError(it) }
            }
        }
    }
    
    private fun onSearchButtonClicked() {
        searchViewModel.search(searchInput.text.toString())
    }
}
```

---

## Recommendations

1. **For Production:** Complete Option A (full refactoring) to ensure consistency and proper testing
2. **For Learning:** Choose Option B (incremental) to understand each component deeply
3. **For Documentation:** If time is limited, complete Option C to provide a clear path forward

The foundation is solid. The repositories provide clean coroutine-based data access, Hilt is configured for dependency injection, and the SearchViewModel demonstrates the pattern. The remaining work is substantial but follows the same pattern established here.

\# TODO: Super Lightweight IPTV Player

\## Multi-Playlist • Android TV • Error-Resilient • Debounced Channel Switching



\## Goals



Build an extremely lightweight IPTV player for older Android TV devices (API 21+).



The application should:



\- Launch directly into the last watched channel.

\- Be fully usable with a TV remote.

\- Support multiple M3U playlists.

\- Remain responsive on low-end hardware.

\- Never crash because of malformed playlists, network failures or invalid streams.

\- Recover gracefully whenever possible.



\---



\# Non-Goals



The application intentionally does \*\*not\*\* implement:



\- User accounts

\- IPTV provider integration

\- Recording

\- Timeshift

\- Chromecast

\- Search

\- Voice control

\- Recommendations

\- Streaming to other devices

\- Heavy UI frameworks



\---



\# Architecture



```

PlayerActivity

&#x20;   │

&#x20;   ▼

MainViewModel

&#x20;   │

&#x20;   ▼

PlaylistRepository

&#x20;   │

&#x20;   ├── Storage

&#x20;   └── Network

```



Rules:



\- PlayerActivity owns the single ExoPlayer instance.

\- ViewModel never directly references ExoPlayer.

\- Repository owns playlist loading and parsing.

\- UI must never perform blocking work.



\---



\# 1. Project Setup



\- \[ ] Android TV application

\- \[ ] minSdk 21

\- \[ ] targetSdk 33+

\- \[ ] Leanback launcher

\- \[ ] Dark theme

\- \[ ] INTERNET permission

\- \[ ] Storage permissions where required

\- \[ ] Kotlin only

\- \[ ] XML layouts only

\- \[ ] No Compose

\- \[ ] No dependency injection framework

\- \[ ] Avoid unnecessary libraries



\---



\# 2. Playlist Parsing



Implement a robust M3U parser.



Requirements:



\- \[ ] Support local files

\- \[ ] Support remote URLs

\- \[ ] Support UTF-8

\- \[ ] Support UTF-8 BOM

\- \[ ] Support CRLF and LF line endings



Extract:



\- \[ ] channel name

\- \[ ] tvg-name

\- \[ ] tvg-logo

\- \[ ] group-title

\- \[ ] stream URL



Each channel must contain:



\- name

\- streamUrl

\- group

\- logoUrl

\- sourceId



Malformed entries:



\- \[ ] Skip invalid entries

\- \[ ] Continue parsing remaining channels

\- \[ ] Never abort entire playlist



\---



\# 3. Playlist Management



Support multiple playlists.



Each playlist stores:



\- unique ID

\- name

\- URL or file URI

\- enabled state

\- last successful update timestamp



Rules:



\- \[ ] Preserve playlist order

\- \[ ] Preserve channel order inside playlists

\- \[ ] Do not automatically sort channels

\- \[ ] Duplicate channels are allowed



\---



\# 4. Persistence



Store configuration using SharedPreferences as JSON.



Persist:



\- \[ ] playlist list

\- \[ ] last successful channel

\- \[ ] last selected playlist



Restore playback using:



\- sourceId

\- streamUrl



If restoration fails:



\- Start at channel 0.



If stored data is corrupt:



\- Reset configuration safely.

\- Never crash.



\---



\# 5. Repository



Responsibilities:



\- \[ ] Load enabled playlists

\- \[ ] Download remote playlists

\- \[ ] Read local playlists

\- \[ ] Merge playlists

\- \[ ] Cache successful downloads



Startup behaviour:



\- Attempt to download remote playlists.

\- If download fails, use cached version.

\- Manual reload always available.



Network:



\- Connect timeout: 10 seconds

\- Read timeout: 15 seconds



\---



\# 6. ViewModel



Expose:



\- merged channel list

\- selected channel

\- playback state

\- loading state

\- error messages



ViewModel never owns ExoPlayer.



\---



\# 7. Player Screen



Single activity.



Contains:



\- fullscreen PlayerView

\- transient information overlay

\- channel list panel

\- settings dialog



Playback continues while menus are open.



\---



\# 8. Information Overlay



Displays:



\- current channel

\- playlist name

\- loading state

\- playback errors



Behaviour:



\- Appears when requested

\- Automatically hides after 3 seconds

\- Never blocks playback



\---



\# 9. Channel List



Opened with LEFT.



Contains:



\- logo

\- channel name

\- source indicator



Behaviour:



\- Playback continues behind list

\- CENTER selects channel

\- RIGHT or BACK closes list

\- Settings button at top



Logo loading:



\- Lazy loaded

\- Placeholder if unavailable

\- Never block scrolling



\---



\# 10. Settings



Support:



\- Add URL playlist

\- Add local playlist

\- Edit playlist

\- Remove playlist

\- Enable/disable playlist

\- Reload playlists



Validation:



Reject:



\- empty URLs

\- unsupported URI schemes

\- duplicate playlist URLs



Display:



\- last update

\- error state

\- loading state



\---



\# 11. Playback



Use exactly one ExoPlayer instance.



Rules:



\- Only one playback request may exist.

\- New requests cancel previous ones.

\- Never prepare multiple streams simultaneously.



Playback flow:



Loading



↓



Preparing



↓



Playing



↓



Error



↓



Recovery



↓



Loading



\---



\# 12. Debounced Channel Switching



Critical feature.



Behaviour:



\- Channel +/- immediately updates UI.

\- Playback waits approximately 300 ms.

\- Additional key presses restart the timer.

\- Only the final selected channel is loaded.



Result:



Rapid surfing prepares only one stream.



\---



\# 13. Error Recovery



Playback failures:



\- network failure

\- unsupported format

\- HTTP errors

\- timeout



Behaviour:



\- Show error message

\- Wait briefly

\- Attempt next channel

\- Continue until a playable channel is found



Rules:



\- Never retry the same channel during one recovery cycle.

\- Stop after every channel has been attempted.

\- If no channels work, display persistent error.

\- Keep application usable.



\---



\# 14. Remote Control



| Key | Behaviour |

|------|-----------|

| Channel Up | Next channel (debounced) |

| Channel Down | Previous channel (debounced) |

| DPAD Center | Toggle overlay / Select |

| LEFT | Open channel list |

| RIGHT | Close channel list |

| UP/DOWN | Navigate channel list |

| BACK | Close menus, otherwise minimise app |

| MENU | Open settings |



\---



\# 15. Edge Cases



Handle gracefully:



\- \[ ] No playlists configured

\- \[ ] Empty playlists

\- \[ ] Disabled playlists

\- \[ ] Invalid M3U

\- \[ ] Invalid URLs

\- \[ ] Network unavailable

\- \[ ] Stream unavailable

\- \[ ] Corrupt saved configuration

\- \[ ] Playlist download failure

\- \[ ] Missing logos



None of these may crash the application.



\---



\# 16. Performance



Target low-end Android TV hardware.



Requirements:



\- One ExoPlayer instance

\- No unnecessary allocations

\- No blocking on UI thread

\- Lazy-load logos

\- Cancel obsolete work

\- Keep dependencies minimal



\---



\# 17. Logging



Log:



\- playlist downloads

\- parser warnings

\- playback failures

\- recovery attempts



Do not spam logs with routine player events.



\---



\# 18. Future Features



Optional:



\- favourites

\- group filtering

\- EPG

\- per-playlist User-Agent

\- playlist reordering



\---



\# 19. Visual Design



The application should evoke the look of a classic digital TV receiver while remaining clean and lightweight.



Colour palette:



\- \[ ] Predominantly black or very dark grey backgrounds

\- \[ ] High-contrast UI elements

\- \[ ] Bright cyan used for focus and selection

\- \[ ] Amber used sparingly for status, loading and warnings

\- \[ ] Error states shown in red



Typography:



\- \[ ] Large, highly readable text suitable for TV viewing distance

\- \[ ] Monospace or retro-digital font for channel numbers

\- \[ ] If available, display the channel number (`tvg-chno`) prominently

\- \[ ] Otherwise display a generated channel number based on the merged channel order



Channel list:



\- \[ ] Styled similarly to a classic TV channel list or EPG

\- \[ ] Large channel number on the left

\- \[ ] Channel name beside it

\- \[ ] Optional logo on the right or beside the name

\- \[ ] Small coloured indicator identifying the source playlist



Focus:



\- \[ ] Clearly visible focus state

\- \[ ] Thick outline or glow

\- \[ ] Slight scale animation when focused

\- \[ ] Focus must remain obvious from typical TV viewing distance



Animations:



\- \[ ] Keep animations minimal

\- \[ ] Simple fades and slides only

\- \[ ] Avoid heavy transitions



Optional polish:



\- \[ ] Optional subtle CRT scanline overlay for menus only

\- \[ ] Never apply visual effects that reduce playback performance



\---



\# 20. First Launch \& Empty State



If no playlists are configured:



\- \[ ] Display a full-screen welcome screen instead of a black player

\- \[ ] Explain that no channels are available yet

\- \[ ] Display the instruction:

&#x20;     "Press LEFT to open Settings and add a playlist."

\- \[ ] Allow Settings to be opened immediately, even with zero channels configured



If all playlists are empty or disabled:



\- \[ ] Display:

&#x20;     "No channels available."

\- \[ ] Prompt the user to check playlist configuration

\- \[ ] Keep the Settings screen accessible at all times



\---



\# Acceptance Criteria



\- \[ ] Starts directly on the last watched channel.

\- \[ ] Works entirely with a TV remote.

\- \[ ] Rapid channel surfing prepares only one stream.

\- \[ ] Invalid playlists never crash the app.

\- \[ ] Network failures never crash the app.

\- \[ ] Failed streams recover automatically.

\- \[ ] Playback continues while menus are open.

\- \[ ] Multiple playlists work simultaneously.

\- \[ ] Startup remains fast using cached playlists.

\- \[ ] Application remains lightweight and responsive on older Android TV devices.




# Box extension for Aniyomi

Standalone Aniyomi extension that plays YouTube videos through Invidious, without exposing the user's IP to YouTube.

## Install

Add this repo link in Aniyomi:

```
https://raw.githubusercontent.com/pepe1784/Box-yuzono/repo/index.min.json
```

## Build

The GitHub Action clones the [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions) source, applies this patch, builds the APK and publishes the `repo` branch.

## Settings

- **Invidious instance**: change the Invidious server URL.
- **Preferred quality**: choose default quality (1080p, 720p, 480p, 360p or DASH).

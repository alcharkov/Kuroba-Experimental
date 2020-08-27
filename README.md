
# Kuroba Experimental (Temp name)

### What is this?
A fork of Kuroba where new features will be implemented and tested before getting merged into the main repo (or maybe 
they won't).
This is an experimental repo so I'm going to do a lot of refactoring and code rewriting to the new Android stack 
(Kotlin, RxJava/Coroutines, Room, Work Manager etc).

The main reason for this fork to come alive is because most of my features are huge, they change a lot of code throughout
the whole app and it's really hard to review them and I have lots of other ideas which will require even more code changes
 so we (with Adamantcheese) decided that it will be easier for both of us if I just do my own fork. 
 
### Where do I get the APK?
For now there is no APK because all the new features are WIP.

### Is this a separate app? Will I have to install this alongside the other Kuroba app?
Yes, because I will be using my own keys to sign the APK.

### Will there be a dev and release versions too?
Yes, most likely.

### Will there be auto updating too?
Yes, most likely.

### F-droid support?
Yes, most likely.

### Can I create issues in this repo?
Yes, if they are related to this particular version (for now the code base is very similar to original Kuroba so the 
bugs will most likely be similar too).

### What are the new features that are currently being worked on or already implemented?
Things to do before first beta version:
- [x] New technological stack (Kotlin, RxJava/Coroutines, Room etc).
- [x] On demand content loading (includes prefetching, youtube videos titles and durations fetching, inlined files size fetching 
etc).
- [x] Third-party archives support. Ability to restore deleted posts/images from third-party archives (4chan only), 
redirect to archives when a thread is deleted, ability to open any thread by it's id (and get automatically redirected to archive if 
the thread is deleted)
- [x] New thread navigation (tabs).
- [x] New in-app navigation (bottom nav bar).
- [x] New bookmarks (they were fully rewritten from scratch, now use way less memory, don't use wakelocks, show separate notifications
per thread (and notifications can be swiped away).
- [x] Lots of other tiny improvements.
- [x] Edge-to-edge theme support.
- [x] New database.

Things to do before first release:
- [ ] New navigation code. Use Conductor instead of the home-made controllers (the code should become cleaner, also this is a great
oppurtunity to simplify current navigation code which is quite complex. This one will probably be implemented some time after the initial 
beta release).
- [ ] Import/export settings (lots of stuff was changed so for now this functionality doesn't work).
- [ ] Fully customizable themes. You will be able to choose colors in app, import/export a theme. (I have an idea on how to do this
but I'm not 100% sure it will work for every view so this feature may not get implemented).

### Why does it not say that this is a fork of Kuroba on github page?
Because Github does not allow creating two separate forks of the same repo. So I decided to create a new, standalone
 repository. This, actually, has benifits, like, it's now possible to use repository search function which Github does
 not allow for forks.
 
### When will it be available? (UPD: 27/8/2020)
So, all of the database work is done. Now that I don't need to drastically change it anymore (maybe some fixes, but nothing serious)
the very first beta is pretty much ready to go. All that is left is to fix some known issues and test it a little bit so that it
won't blow up right after I release it (but still expect bugs since like more than half of the app was rewritten) and also implement 
new auto-updater and crashlog uploader. 

Now, if nothing serious comes up then the date of the first beta release will be, let's say **in ~two weeks on Friday September 11th**.

# 

Kuroba is a fast Android app for browsing imageboards, such as 4chan and 8chan. It adds inline replying, thread watching, notifications, themes, pass support, filters and a whole lot more. It is based on Clover by Floens, but has additional features added in because Floens doesn't want to merge PRs. Credits to K1rakishou for a number of features.
#### [A full feature list can be found here.](https://gist.github.com/Adamantcheese/0c15a36ab983e7829f91f1248ab28844)

##### Currently supported sites
Sites are supported on a PR basis. You want the site, you do the work.
- 4Chan
- Dvach
- 8Kun (thanks to @jirn073-76)
- 420Chan (thanks to @Lolzen)
- Lainchan
- Sushichan
- Wired-7 (thanks to @Wired-7)

## License
[Kuroba is GPLv3](https://github.com/Adamantcheese/Kuroba/blob/multi-feature/COPYING.txt), [licenses of the used libraries.](https://github.com/Adamantcheese/Kuroba/blob/multi-feature/Kuroba/app/src/main/assets/html/licenses.html)

Hi!

This is a quick website I setup to showcase myself (Om Mahida) and my experience.

It used to be a template from HTML5 but I switched to my own design using Semantic-UI styling which I find extremely simple and not bloated. 



#random

Note: Below doesn't work even though I thought it did, I'll try finding another workaround. Just use play.spotify.com and use ublock or better pay for Spotify :P

Add (How to block Spotify ads on OSX)

1) Close Spotify
2) Navigate to your Applications folder, right click Spotify & select 'Open Package Contents'
3) Navigate to 'Contents/Resources/Apps' and remove ad.spa
4) Open Terminal & type 'sudo nano /etc/hosts', then type in your password & press enter.
5) Add the following lines of code: 
0.0.0.0 pubads.g.doubleclick.net
0.0.0.0 securepubads.g.doubleclick.net
6) hit Ctrl+O then hit enter on the keyboard
7) Reopen Spotify

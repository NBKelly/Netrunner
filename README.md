# Netrunner 1996
This is a fork of the netrunner client used for jinteki.net, with the goal of porting over all the features, functionality, and cards of 1996 netrunner.

## Implementation status:
* 345 / 584 cards implemented so far
* 176 of those with unit tests
* The ONR trace mechanic has been implemented, with all the quirks (cancelling, cost multiplication, base link cards, post-trace link adjustment, max strength manipulation, secret bidding, etc)
* Systems developed to allow for the unique virus mechanics (counters have effects outside of their programs)
* Two special ID's have been created, which some mechanics rely on (a small handful of the viruses, ONR style purging)
* ONR cards that refer to trace do not interact with ANR cards that refer to trace. Other than that, almost all cards should be (mostly) interoperable

This little graph is a visual representation of the implementation status so far. Green for tests, orange for no tests, and red for "I'm not implementing that (only 2 hits so far)"

![1996 Implementation Graph](graph-implementation.jpg)

## TODO
* Make an ONR legal format
* card art for the special id's (even if it's bad)
* seeded (in terms of repeatable rng) draft/sealed generator to allow for an easily accessible sealed format
* describe-counters command that lets you see the types and quantities of counters on a card (there are lots of them...)
* explain-counter command that explains what a counter does (what does a militech counter do? what about an I-Spy counter?)
* Ephemeral counters that live in servers

# Netrunner in the browser

Hosted at [http://www.jinteki.net](http://www.jinteki.net). [Example of gameplay](https://www.youtube.com/watch?v=cnWudnpeY2c).

![screenshot](http://i.imgur.com/xkxOMHc.jpg)

## Card implementation status

[Card rules implementation status](https://docs.google.com/spreadsheets/d/1ICv19cNjSaW9C-DoEEGH3iFt09PBTob4CAutGex0gnE/pubhtml)

## Development

### Quickstart

*(There's a [docker](#using-docker) section down there!)*

Install [Leiningen](https://leiningen.org/),
[NodeJS](https://nodejs.org/en/download/package-manager/#macos) and
[MongoDB](https://docs.mongodb.com/manual/installation/).

This project runs on Java 8. If you're on OSX or Linux, we recommend using
[jenv](https://github.com/jenv/jenv/blob/master/README.md) to manage your java environment.

You can check your setup by running:

    $ lein version
    Leiningen 2.9.6 on Java 16.0.1 OpenJDK 64-Bit Server VM

Your exact version numbers below may vary, but we require Java 1.8+.

Populate the database and create indexes using:

    $ lein fetch [--no-card-images]
    1648 cards imported

    $ lein create-indexes
    Indexes successfully created.

You can optionally pass `--no-card-images` if you don't want to download images from
[NetrunnerDB](https://netrunnerdb.com/), as this takes a while. See `lein fetch help`
for further options.

To install frontend dependencies, run:

    $ npm ci
    added 124 packages, and audited 125 packages in 2s

To compile CSS:

    $ npm run css:build
    compiled resources/public/css/netrunner.css

Optionally you can say `npm run watch:css` to watch for changes and automatically
recompile.

Compile ClojureScript frontend:

    $ npm run cljs:build
    [:app] Compiling ...
    [:app] Build completed. (238 files, 0 compiled, 0 warnings, 22.18s)

Finally, launch the webserver and the Clojure REPL:

    $ lein repl
    dev.user=>

and open [http://localhost:1042/](http://localhost:1042/).

### Using Docker

You'll need to install [Docker](https://docs.docker.com/get-docker/) and [Docker-Compose](https://docs.docker.com/compose/install/). After that, just run `$ docker-compose up --build` in the project directory (or do the GUI-equivalent of this). If this fails because it "couldn't fetch dependencies", try again, it was just a networking error.

It can take a while. You'll see lots of messages, so just wait until you see something like `netrunner-server-1 | nREPL server started on port 44867`. After that, you can visit [http://localhost:1042/](http://localhost:1042/) and the server should be running.

While coding clojure it's important to have a REPL connection going. The server's REPL is configured to always run on port `44867`, so you can connect using `$ lein repl :connect nrepl://localhost:44867` (or the program of your preference, like your code editor).

Now, let's populate the database and create indexes. First, let's open a terminal inside the server container: `$ docker exec -it netrunner-server-1 /bin/bash`. Now, inside this new therminal, we'll run these two commands: *The `--no-card-images` is optional, and removing it causes the card images to be downloaded, which can be slower.*

```
 $ lein fetch --no-card-images
    1648 cards imported

 $ lein create-indexes
    Indexes successfully created.
```

After this, just restart the server by running `(restart)` in the REPL.

To do testing, you run them inside the container: `$ docker exec -it netrunner-server-1 /bin/bash` and then `$ lein kaocha`.
### Tests

To run all tests:

    $ lein kaocha
    Ran 2640 tests containing 44704 assertions.
    0 failures, 0 errors.

To run a single test file:

    $ lein kaocha --focus game.cards.agendas-test
    Ran 216 tests containing 3536 assertions.
    0 failures, 0 errors.

Or a single test:

    $ lein kaocha --focus game.cards.agendas-test/fifteen-minutes
    Ran 1 tests containing 29 assertions.
    0 failures, 0 errors.

For more information refer to the [development guide](https://github.com/mtgred/netrunner/wiki/Getting-Started-with-Development).

### Further reading

- [Development Tips and Tricks](https://github.com/mtgred/netrunner/wiki/Development-Tips-and-Tricks)
- [Writing Tests](https://github.com/mtgred/netrunner/wiki/Tests)
- "Profiling Database Queries" in `DEVELOPMENT.md`

## License

Jinteki.net is released under the [MIT License](http://www.opensource.org/licenses/MIT).

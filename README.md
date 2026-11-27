# PingBot

A small Telegram bot that monitors a host's TCP availability and sends notifications when its status changes.

PingBot was created in 2022 during Russia's full-scale invasion of Ukraine, when power outages frequently disrupted local websites.

<a href="https://vshymanskyy.github.io/StandWithUkraine">
  <img src="https://raw.githubusercontent.com/vshymanskyy/StandWithUkraine/main/banner2-direct.svg" alt="Stand with Ukraine">
</a>

## Requirements

- JDK 17.
- A Telegram bot token from [BotFather](https://t.me/BotFather).
- Network access to Telegram and the host you want to monitor.

The Gradle wrapper is included; you do not need to install Gradle separately. The first build downloads Gradle and the project's dependencies.

## Quick start

Clone this repository and open its directory. Set the required environment variables, then start the bot:

```bash
export BOT_TOKEN="your-telegram-bot-token"
export HOST="example.com"
./gradlew run
```

On Windows, use PowerShell:

```powershell
$env:BOT_TOKEN = "your-telegram-bot-token"
$env:HOST = "example.com"
.\gradlew.bat run
```

Open your bot in Telegram and send it a message. PingBot saves your chat ID, confirms your subscription, and replies with the latest availability status. Subsequent messages return the latest status, and all subscribed chats receive a notification when the status changes.

Stop the process with Ctrl+C. Keep your bot token private and out of source control.

![Example PingBot conversation](https://user-images.githubusercontent.com/37211863/202767440-ee290149-35dc-4a81-8ae1-d6c1b7e2a65e.png)

## Configuration

All settings come from environment variables; no source edits are needed.

| Variable | Default | Description |
| --- | --- | --- |
| `BOT_TOKEN` | Required | Telegram bot token from BotFather. |
| `HOST` | Required | Hostname or IP address, such as `example.com` or `192.0.2.1`. Use a bare host without a URL scheme or path. |
| `PORT` | `80` | TCP port to check, from 1 to 65535. |
| `CHECK_INTERVAL_MILLIS` | `60000` | Delay between completed checks, in milliseconds. Must be positive. |
| `CONNECT_TIMEOUT_MILLIS` | `5000` | TCP connection timeout, in milliseconds. Must be a positive 32-bit integer. |
| `CHAT_IDS_FILE` | `chat_ids.txt` | File used to persist subscriber chat IDs. Relative paths use the process's working directory. |

For example, to check port 443 every 30 seconds:

```bash
export PORT="443"
export CHECK_INTERVAL_MILLIS="30000"
./gradlew run
```

The bot validates configuration and loads subscribers before it starts monitoring. Missing required values, invalid settings, or an unreadable subscriber file stop startup with an error.

## How monitoring works

1. PingBot opens a TCP connection to the configured host and port to establish the initial status.
2. After each configured delay, it checks again.
3. When the status changes, it queues a notification for every subscribed chat.
4. Incoming Telegram messages subscribe new chats and return the status from the latest completed check.

The initial check does not broadcast a notification. Telegram messages are sent asynchronously, so delivery does not block monitoring.

A successful TCP connection means the port is reachable. It does **not** verify HTTP responses, page content, TLS certificates, or whether a website is working correctly. Checks observe availability from the machine running PingBot.

Notifications are best effort: delivery failures are logged, and failed notifications are not saved for later delivery. A single failed check changes the status immediately; there is no outage threshold or flapping suppression.

## Subscriber data and logs

Chat IDs are stored as plain numbers in `chat_ids.txt`, one per line for new subscriptions. Existing files remain compatible, including whitespace-separated IDs and duplicate entries; each chat receives one notification per transition. Invalid entries stop startup so they can be corrected.

The file is created if it does not exist. Its parent directory must already exist and be writable. Keep this file on persistent storage if you want subscriptions to survive redeployment.

There is no unsubscribe command yet. To remove a subscription, stop the bot, remove the chat ID from the file, and restart it. Run one bot instance per token and subscriber file.

Logs use Java's standard logging and go to standard error. To append them to a file:

```bash
./gradlew run 2>>pingbot.log
```

If you are upgrading from the original version, move the token and host from `Configuration.java` into environment variables. The default subscriber file is unchanged; the bot no longer creates `log.txt` automatically.

## Development

Run the tests and build the application:

```bash
./gradlew build
```

Tests cover configuration validation, subscriber persistence and concurrency, status transitions, local TCP checks, and Telegram message handling with a test client. They do not require a real bot token or contact Telegram.

To generate a runnable installation:

```bash
./gradlew installDist
build/install/pingbot/bin/pingbot
```

Set the same environment variables before running the installed application. On Windows, use `build\install\pingbot\bin\pingbot.bat`.

The code stays in a single package with a few focused components:

| Component | Responsibility |
| --- | --- |
| `Configuration` | Load and validate environment settings. |
| `TcpAvailabilityChecker` | Check whether the configured TCP port accepts connections. |
| `SubscriberStore` | Load, deduplicate, and persist subscriptions safely across threads. |
| `AvailabilityMonitor` | Schedule checks and detect status changes. |
| `PingBot` | Handle Telegram updates and send messages. |
| `Main` | Assemble the application and register shutdown handling. |

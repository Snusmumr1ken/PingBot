## PingBot - Telegram bot that checks website availability

<a href="https://vshymanskyy.github.io/StandWithUkraine">
		<img src="https://raw.githubusercontent.com/vshymanskyy/StandWithUkraine/main/banner2-direct.svg">
	</a>

This bot was created during the 2022 russian invasion of Ukraine when the country faced problems with electricity and local websites were frequently down.
With this bot, people can monitor the availability of a chosen website.

### Usage

1. Clone this repository
2. In the `src/main/java/pingbot/Configuration.java` file set:
    1. `BOT_TOKEN` variable with a token you got from [BotFather](https://t.me/BotFather)
    2. `HOST` variable with a link to the website you want to ping
3. Run it by writing in a root `./gradlew run`
4. Now, you can write to your bot, and it will send you updates on a website availability

![usage example](https://user-images.githubusercontent.com/37211863/202767440-ee290149-35dc-4a81-8ae1-d6c1b7e2a65e.png)

### Bot logic
1. Initializes logging by writing to a file.
2. Processes a file containing chat IDs that are needed to write messages to users:
  1. If the file exists, the program parses it and saves all IDs in a list.
  2. If the file does not exist, the program creates it.
3. Runs a bot listener for updates (user messages), which processes updates using the processUpdate(Update update) method.
4. Runs an infinite loop in a new thread that checks the availability of a website. If the website's status changes, all users from the chatIds list receive an update.

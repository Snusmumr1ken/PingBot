## PingBot - Telegram bot which checks website availability

This bot was created during 2022 Russian invasion of Ukraine. Ukraine faced problems with electricity. Local websites were down a lot.  
With this bot, people can monitor an availability of a choosen website, for example, learning system of a university.

### Usage

1. Clone this repository
2. In the `src/main/java/pingbot/Configuration.java` file set:
    1. `BOT_TOKEN` variable with a token you got from [BotFather](https://t.me/BotFather)
    2. `HOST` variable with a link to the website you want to ping
3. Run it by writing in a root `./gradlew run`
4. Now, you can write to your bot, and it will send you updates on a website availability

![usage example](https://user-images.githubusercontent.com/37211863/202767440-ee290149-35dc-4a81-8ae1-d6c1b7e2a65e.png)

### Bot logic
1. Initializes logging. Logging is implemented by simple writing in a file.
2. Processes chat IDs file. Chat ids are needed to write message to users.
    1. If there is a file, program will parse it. It will save all IDs from a file, in a list.
    2. If there is no such file, program will create it.
3. Runs bot listener for updates (user messages). All messages goes there and are procesed by `processUpdate(Update update)` method.
4. Runs infinite loop in a new thread, which checks for a website availability. If website status changes, all users from a `chatIds` list get an update.

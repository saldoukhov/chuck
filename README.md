# Chuck

Chuck is a command-line interface to OpenAI applications such as ChatGPT.

## Requirements
+ Updated XCode.  If you get an error when runnning `xcrun`, do this:
   + `sudo rm -rf /Library/Developer/CommandLineTools`
   + `xcode-select --install`

## Building and Running
+ `$ ./gradlew build`

The output is in `build/bin/native/releaseExecutable`

If you have trouble compiling because the libcurl dependencies missing, install libcurl, f.e.
```shell
sudo apt-get update
sudo apt-get install -y libcurl4-openssl-dev
```

## Using Chuck
Set an environment variable with your OPENAPI key from platform.openai.com:
```shell
# OpenAI Platform                                                                                                                                                                            
export OPENAI_API_KEY="srmo...39"                                                                                                                  
```
Set a useful alias:
```shell
$ alias chuck='..../build/bin/native/releaseExecutable/chuck.kexe gpt-3.5-turbo'
```

```shell
$ chuck
Hello, I'm Chuck, your OpenAI assistant. Ask me questions, or type 'bye' to exit, 'ok' to start a new conversation.
🤖 What is the GDP of Tanzania
💬
The GDP of Tanzania in 2020 was approximately $62.3 billion USD.
🤖 What is the GDP of England
💬The GDP of England in 2020 was approximately $2.6 trillion USD.
🤖 bye
$
```

To provide more context, Chuck sends all previous queries to GPT.

To clear the context, type `ok`.

## Miscellaneous
To get a list of available GPT language models, run:
```shell
$ curl https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_API_KEY" | grep 'id"' | grep gpt
      "id": "gpt-3.5-turbo",
      "id": "gpt-3.5-turbo-0301",
```
To get a list of all language models, run:
```shell
$ curl https://api.openai.com/v1/models   -H "Authorization: Bearer $OPENAI_API_KEY" | grep 'id"' | grep -v modelperm | grep -v snapperm
      "id": "babbage",
      "id": "davinci",
      "id": "gpt-3.5-turbo",
      "id": "babbage-code-search-code",
      "id": "text-similarity-babbage-001",
      "id": "text-davinci-001",
      "id": "ada",
      "id": "curie-instruct-beta",
      "id": "gpt-3.5-turbo-0301",
      "id": "babbage-code-search-text",
      "id": "babbage-similarity",
      ...
```

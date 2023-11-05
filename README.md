# form

A simple (but opinionated) tool for managing project-specific AWS resources with CloudFormation. 

## Installation 

If you've cloned `form` locally and want to install it (or a modified version) from source you can: 

```
just install
```

Otherwise, if you want to install a specific release from GitHub, on a CI machine for example: 

```
clj -Ttools install com.github.codeasone/form '{:git/tag "v0.1.0"}' :as form
```

Once installed on a system, `form` can be run anywhere using `clj -Tform` 

To uninstall: 

```
clj -Ttools remove :tool form
```

## Prerequisites 

Clojure >= `1.10.3.933` (with support for [tools](https://clojure.org/releases/tools#v1.10.3.933)) on the machine or container on which you want to run it. 

In order to work `form` needs access credentials and uses the AWS `BasicCredentialsProvider` to obtain them. 

It will derive credentials from `.aws/{credentials,config}` if those files exist, or via `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` environment variables.

## Mental model 

Four parameters establish context: 

- `:environment` (e.g. `staging`, `prod`, `uat`, `qa`) 
- `:application` (your project name) 
- `:technology` (e.g. `s3`, `rds`, etc) 
- `:version` (major version number) 

Any CloudFormation stacks managed by `form` are named according to these parameters. 

For example, if you're provisioning an `rds` stack for a service called `accounts` in `staging` the resulting stack name will be: 

```
staging-accounts-rds-v1
```

CloudFormation template files corresponding to these stacks must reside within a `cloudformation/` folder in the directory in which you're running the tool (usually the base directory of your project). 

Each template file must be named `template-<technology>.yml` according to what its constituent resources relate to, for example: 

```
cloudformation/
├── template-main.yml
├── template-rds.yml
├── template-s3.yml
└── ...
```

__Note:__ whilst it's sensible to group resources according to AWS service types such as `rds`, the `<technology>` name can be anything you like. For instance, in the list of example templates shown above, `main` could be used as a name for grouping ECS resources associated with the "main" application of the project. 

If you're not interested in partitioning the resources for your project, you *could* have one `<technology>` grouping such as `all`, and one corresponding `template-all.yml`, but managing the lifecycle of all resources in one stack has its downsides, so this usage is not advised. 

## Usage 

Here's a fully-specified invocation of the `provision` command. It provisions the `s3` resources in `cloudformation/template-s3.yml` for the `accounts` application on `staging`: 

```
clj -Tform provision :environment staging :application accounts :technology s3 :version 1 :unique-id foobar
``` 

You can try running this yourself right away because an example `template-s3.yml` template managing a single bucket is provided in the `cloudformation/` folder of this repo. 

Just be sure to choose a `:unique-id` that ensures the underlying bucket name does not collide with one that already exists.

__Note:__ `:unique-id` argument corresponds to an additional parameter that is specific to the `s3` stack whose name is `UniqueId` (Pascal-case) within the corresponding `template-s3.yml`.

Here's a preview of what you'll see when you create the `staging-accounts-s3-v1` stack using the `provision` command above: 

<img src="./images/form-provision.png" alt="An example of running form provision" width="800"/>

If `:version` is not provided then the default value of `1` is used and resulting stacks will be suffixed `v1`. Many projects will never extend beyond `v1`, but if needed it's good to know that the organisational scheme scales to accommodate it. 

By default, `form` runs with `:interactive true` and will prompt you to review-and-approve any changes, with handy context-specific AWS console links. 

For CI usage, this mode can be disabled with `:interactive false`. 

## Safety 

If `:environment` is equal to `prod` or `production` then any stacks created with `form` have termination protection enabled automatically, making them harder to accidentally delete, in the event that you have sufficient IAM privileges to delete stacks to begin with.

## Limitations 

Currently there is no `clj -Tform deprovision` support, but this will be added to a subsequent release. 

Until then, to delete stacks you'll need to use the AWS console or another tool. 

## Why not just use the awscli tool? 

Adopting `form` into your project is as much about introducing a structured and consistent approach to naming and grouping AWS resources, as being a handy tool. 

It works well in environments consisting of 10s or 100s of services, deployed across multiple environments, where there needs to be a quick and unambiguous answer to the questions like "what resources is the project `accounts` using in `staging`?" 

The terminal-based UX of `form` has evolved through numerous incarnations over the past 7 years or so. Variants of it have been used by multiple teams for IaC development and debugging, to deploy production systems, as well as in the context of numerous CI testing rigs. 

The visual output, handy console links, and timestamped status updates, together with the interactive features, are field tested and have proven to be useful enough to merit the creation of this tool. 

## Development 

If you're using [asdf](https://github.com/asdf-vm/asdf) then you can `asdf install` as a `.tool-versions` is provided.

If not, make sure you have compatible `java`, `clojure`, and `just` tool versions installed on your system.

With its current incarnation in Clojure code, `form` is easy to understand, test, and debug via standard REPL-based workflows.

To run the integration tests, first bring up `localstack` with `docker-compose up` and when port `4566` is available you can: 

```
just test
```

Or alternatively invoke tests from within your editor or REPL, as you prefer.

## Some ideas and next steps 

### Must  
- Add a `clj -Tform help` command and output usage information whenever `clj -Tform` is called without any command 
- Add more tests and examples 
- Add GitHub `test` workflow to regression test future changes 

### Should 
- Document usage of [AWS System Manager Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html) as a means of handling additional stack parameters, including references to resources external to the project under development, which may have been provisioned manually or using `terraform`
- Automatically use delegated account access credentials based on `:environment` if matching profiles and roles are defined in `.aws/credentials` (falling back to `default` profile)
- Support `clj -Tform deprovision`  of non-production stacks, to support programmatic tear-down of `nightly` test environments
- Improve error handling and messaging 

### Could 
- Add functions to answer questions like "what resources is the project `accounts` using in `staging`?" in a concise and helpful way
- Reflect more CloudFormation best-practices in how `form` works 

### Maybe 
- Release `form.cloudformation` as a library 
- Default `:application` to the GitHub project name if not explicitly specified 
- Add a configuration file enabling default behaviours to be customised 

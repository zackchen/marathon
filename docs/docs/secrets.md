---
title: Secret Configuration
---

Marathon has a pluggable interface for secret store providers. You can start Marathon respecting these plugins and you can write plugins to handle the shown secret api.
For further information please see [Extend Marathon with Plugins](plugin.md).

You have two options to use these kind of secrets. The first option is to inject the value of the configured secret as environment variable.
The second option is to inject the value of the configured secret as file to a configured path in the Mesos Sandbox.

**Important**: Marathon will only provide the API to configure and store these secrets. You need to write and register a plugin which interprets these secrets.


# Environment variable based secrets
It is possible to define secrets with a given environment variable name. They can be interpreted to be injected as environment variable by a marathon plugin.

## Example configuration of environment variable based secrets for app definitions

```json
{
  "id": "app-with-secrets",
  "cmd": "sleep 100",
  "env": {
    "DATABASE_PW": { 
      "secret": {
        "source": "databasepassword"
      }
    }
  }
}
```

## Example configuration of environment variable based secrets for pod definitions

```
{
  "id": "/pod-with-secrets",
  "containers": [
    {
      "name": "container-1",
      "exec": {
        "command": {
          "shell": "sleep 1"
        }
      }
    }
  ],
  "environment": {
    "DATABASE_PW": { 
      "secret": {
        "source": "databasepassword"
      }
    }
  }
}
```


# File based secrets
It is possible to define secrets with a given mount path. They can be interpreted as file based secrets by a marathon plugin.

## Example configuration of file based secrets for app definitions

```json
{
  "id": "app-with-secrets",
  "cmd": "sleep 100",
  "container": {
    "volumes": [
      {
        "containerPath": "path",
        "secret": {
          "source": "databasepassword"
        }
      }
    ]
  }
}
```

## Example configuration of file based secrets for pod definitions

```
{
  "id": "/pod-with-secrets",
  "containers": [
    {
      "name": "container-1",
      "exec": {
        "command": {
          "shell": "sleep 1"
        }
      },
      "volumeMounts": [
        {
          "name": "secretvolume",
          "mountPath": "path/to/db/password"
        }
      ]
    }
  ],
  "volumes": [
    {
      "name": "secretvolume",
      "secret": {
        "source": "databasepassword"
      }
    }
  ]
}
```

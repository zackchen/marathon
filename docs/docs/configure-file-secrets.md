---
title: Secret Configuration
---

It is possible to define secrets with a given mount path. They can be interpreted as file based secrets by a marathon plugin.

# Example configuration of secrets for app definitions

```json
{
  "id": "app-with-secrets",
  "container": {
    "volumes": [
      {
        "containerPath": "path",
        "secret": {
          "source": "/path/to/my/secret"
        }
      }
    ]
  }
}
```

# Example configuration of secrets for pod definitions

```
{
  "id": "/pod",
  "containers": [
    {
      "name": "container-1",
      "volumeMounts": [
        {
          "name": "secretvol",
          "mountPath": "path"
        }
      ]
    }
  ],
  "volumes": [
    {
      "name": "secretvol",
      "secret": {
        "source": "/path/to/my/secret"
      }
    }
  ]
}
```

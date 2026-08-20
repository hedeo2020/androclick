# credentials/

This folder holds local secrets for this project (GitHub token, etc). Everything
in here except this README is **gitignored** (see `../.gitignore`) so nothing
gets committed or pushed by accident.

## GitHub token

To let Claude push to your GitHub repo for this project, drop a fine-grained
personal access token (repo scope, ideally limited to just the target repo)
into `credentials/github_token` (plain text, just the token, no quotes/newline
needed either way):

```
echo -n "ghp_xxxxxxxxxxxxxxxxxxxx" > credentials/github_token
```

Also add the target repo URL to `credentials/github_repo` (one line, e.g.
`https://github.com/<you>/<repo>.git`).

Once both are present, git remote/push setup will read them from here rather
than needing the token pasted into chat.

## Proxmox / android-dev container

No separate credentials are needed for this — SSH access to the build
container is already configured on this machine via the `android-dev` alias
in `~/.ssh/config` (jumps through `proxmox-server` over Tailscale). See
`../ANDROID-DEV-ENVIRONMENT.md` for details.

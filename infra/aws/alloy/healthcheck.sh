#!/bin/bash
set -eu

# The pinned image includes Bash, but not curl or wget. Docker enforces timeout.
exec 3<>/dev/tcp/127.0.0.1/12345
printf 'GET /-/ready HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
IFS= read -r status <&3
[[ "$status" == *" 200 "* ]]

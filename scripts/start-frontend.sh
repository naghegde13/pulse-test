#!/bin/bash
set -e

cd /Users/aameradam/projects/dev/PULSE/frontend

if [ ! -d node_modules ]; then
  npm ci
fi

npm run dev

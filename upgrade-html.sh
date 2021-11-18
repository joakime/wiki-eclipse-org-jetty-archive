#!/bin/bash

HTML="$1"

cp "$HTML" /tmp/orig.html
cat /tmp/orig.html | html2xhtml > "$HTML"

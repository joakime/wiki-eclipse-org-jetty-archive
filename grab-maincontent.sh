#!/bin/bash

HTML="$1"

MAINHTML=${HTML/.html/.main.html}

xmllint --xpath "//div[@id='mainContent']" "$HTML" > "$MAINHTML"


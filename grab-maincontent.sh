#!/bin/bash

IFS=$'\n'
for PAGE in $(cat names.txt)
do
  RAWHTML="raw/$PAGE.html"
  OUTHTML="clean/$PAGE.html"
  OUTDIR=$(dirname $OUTHTML)
  echo "Grabbing from $RAWHTML to ($OUTDIR) $OUTHTML"
  if [ ! -d "$OUTDIR" ]
  then
    mkdir -p "$OUTDIR"
  fi
  cat <<HTMLHEAD >> "$OUTHTML"
<html>
<head>
    <title>Jetty WTP Plugin</title>
    <link rel="stylesheet" media="screen, print" href="wiki-style.css"/>
</head>
<body>
HTMLHEAD
  xmllint --xpath "//div[@id='mainContent']" "$RAWHTML" >> "$OUTHTML"
  cat <<HTMLFOOT >> "$OUTHTML"
</body>
</html>
HTMLFOOT
done
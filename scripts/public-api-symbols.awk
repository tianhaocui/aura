/^### / {
  owner = substr($0, 5)
  accessible = 0
  next
}

/^(public|protected)[[:space:]]/ {
  accessible = 1
  printf "class\t%s\t%s\n", owner, owner
  printf "type-signature\t%s\t%s\n", owner, $0
  next
}

/^Compiled from / || /^[[:space:]]*$/ || /^}$/ { next }

accessible && owner != "" {
  signature = $0
  gsub(/\t/, " ", signature)
  printf "public-signature\t%s\t%s\n", owner, signature
}

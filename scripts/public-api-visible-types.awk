BEGIN { FS = "\t" }

{
  candidate[$1] = 1
  outer[$1] = $2 == "-" ? "" : $2
}

function visible(owner) {
  if (!(owner in candidate)) return 0
  if (state[owner] == 2) return result[owner]
  if (state[owner] == 1) return 0
  state[owner] = 1
  result[owner] = outer[owner] == "" || visible(outer[owner])
  state[owner] = 2
  return result[owner]
}

END {
  for (owner in candidate) {
    if (visible(owner)) print owner
  }
}

export function javaRuntimeVersion(properties) {
  const matches = [...properties.matchAll(/^\s*java\.runtime\.version = (\S+)\s*$/gmu)];
  if (matches.length !== 1) throw new Error("Java runtime version is missing or ambiguous");
  const version = matches[0][1].replace(/-LTS$/u, "");
  if (!/^[1-9][0-9]{0,2}(?:\.[0-9]+){0,3}(?:-[a-z0-9.]+)?(?:\+[0-9]+)?$/iu.test(version))
    throw new Error("Java runtime version is invalid");
  return version.replace(/(?:\.0)+(?=(?:-[a-z0-9.]+)?(?:\+[0-9]+)?$)/iu, "");
}

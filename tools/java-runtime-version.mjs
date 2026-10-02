export function javaRuntimeVersion(properties) {
  const matches = [...properties.matchAll(/^\s*java\.runtime\.version = (\S+)\s*$/gmu)];
  if (matches.length !== 1) throw new Error("Java runtime version is missing or ambiguous");
  const value = matches[0][1];
  const parsed =
    /^([1-9][0-9]*(?:\.(?:0|[1-9][0-9]*))*)(?:(?:-([A-Za-z0-9]+)(?:\+(0|[1-9][0-9]*))?(?:-([A-Za-z0-9.-]+))?)|(?:\+((?:0|[1-9][0-9]*)?)(?:-([A-Za-z0-9.-]+))?))?$/u.exec(
      value,
    );
  if (!parsed || value.endsWith("+") || value.endsWith("-") || (parsed[5] === "" && !parsed[6]))
    throw new Error("Java runtime version is invalid");
  const numbers = parsed[1].split(".");
  if (numbers.length > 1 && numbers.at(-1) === "0") throw new Error("Java runtime version is invalid");
  const build = parsed[3] ?? parsed[5];
  if ([...numbers, ...(build ? [build] : [])].some((number) => Number(number) > 2_147_483_647))
    throw new Error("Java runtime version exceeds Java int range");
  return `${numbers.join(".")}${parsed[2] ? `-${parsed[2]}` : ""}${build ? `+${build}` : ""}`;
}

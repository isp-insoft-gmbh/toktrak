export async function publishRelease({
  existing,
  version,
  ensureDraft,
  pushVersioned,
  latestTag,
  publishDraft,
  pushLatest,
  verifyLatest,
  markLatest,
}) {
  const record = await ensureDraft(!existing);
  if (!existing) await pushVersioned();
  await publishDraft(record);
  if (version !== (await latestTag())) return;
  await pushLatest();
  await verifyLatest();
  await markLatest(record);
}

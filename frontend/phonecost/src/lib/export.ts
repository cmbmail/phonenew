const BOM = '\uFEFF';

interface ExportColumn {
  title: string;
  dataIndex: string;
  render?: (value: unknown, record: Record<string, unknown>) => string;
}

/**
 * Export data as CSV file with BOM (for Excel Chinese compatibility)
 */
export function exportCSV(
  filename: string,
  columns: ExportColumn[],
  data: Record<string, unknown>[],
) {
  // Header row
  const header = columns.map(c => `"${c.title}"`).join(',');

  // Data rows
  const rows = data.map(row => {
    return columns.map(col => {
      const raw = row[col.dataIndex];
      let value: string;
      if (col.render) {
        value = col.render(raw, row);
      } else if (raw == null) {
        value = '';
      } else {
        value = String(raw);
      }
      // Escape quotes and wrap in quotes
      return `"${value.replace(/"/g, '""')}"`;
    }).join(',');
  });

  const csv = BOM + header + '\n' + rows.join('\n');

  // Trigger download
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename.endsWith('.csv') ? filename : `${filename}.csv`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

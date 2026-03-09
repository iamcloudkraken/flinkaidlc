import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { createTenant } from '../api/tenants';

interface RegistrationForm {
  name: string;
  slug: string;
  contactEmail: string;
  maxPipelines: string;
  maxTotalParallelism: string;
}

export default function TenantRegistrationPage() {
  const navigate = useNavigate();

  const [form, setForm] = useState<RegistrationForm>({
    name: '',
    slug: '',
    contactEmail: '',
    maxPipelines: '10',
    maxTotalParallelism: '100',
  });

  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);

  /**
   * fidSecret is shown ONCE after successful registration and cleared
   * when the user navigates away (component unmounts).
   * It is never stored in any persistent storage.
   */
  const [oneTimeFidSecret, setOneTimeFidSecret] = useState<{
    fid: string;
    fidSecret: string;
  } | null>(null);
  const [copied, setCopied] = useState(false);

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
    // Clear field-level errors on change
    if (fieldErrors[name]) {
      setFieldErrors((prev) => {
        const next = { ...prev };
        delete next[name];
        return next;
      });
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});

    const maxPipelines = parseInt(form.maxPipelines, 10);
    const maxTotalParallelism = parseInt(form.maxTotalParallelism, 10);

    if (isNaN(maxPipelines) || maxPipelines < 1) {
      setFieldErrors((prev) => ({
        ...prev,
        maxPipelines: 'Must be a positive integer.',
      }));
      return;
    }
    if (isNaN(maxTotalParallelism) || maxTotalParallelism < 1) {
      setFieldErrors((prev) => ({
        ...prev,
        maxTotalParallelism: 'Must be a positive integer.',
      }));
      return;
    }

    setLoading(true);
    try {
      const result = await createTenant({
        name: form.name.trim(),
        slug: form.slug.trim(),
        contactEmail: form.contactEmail.trim(),
        maxPipelines,
        maxTotalParallelism,
      });

      // Show the secret one time. On navigation the component unmounts and state is cleared.
      setOneTimeFidSecret({ fid: result.fid, fidSecret: result.fidSecret });
    } catch (err: unknown) {
      if (axios.isAxiosError(err) && err.response?.status === 400) {
        const details = (err.response.data as { details?: { field: string; message: string }[] })?.details;
        if (details) {
          const errs: Record<string, string> = {};
          for (const d of details) {
            errs[d.field] = d.message;
          }
          setFieldErrors(errs);
        } else {
          const msg = (err.response.data as { message?: string })?.message;
          setError(msg ?? 'Registration failed.');
        }
      } else {
        setError('Registration failed. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  }

  async function handleCopy() {
    if (!oneTimeFidSecret) return;
    await navigator.clipboard.writeText(
      `FID: ${oneTimeFidSecret.fid}\nSecret: ${oneTimeFidSecret.fidSecret}`,
    );
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  if (oneTimeFidSecret) {
    return (
      <div className="max-w-lg mx-auto mt-16">
        <div className="bg-white shadow-md rounded-lg p-8">
          <h1 className="text-2xl font-bold text-gray-800 mb-2">
            Tenant Registered!
          </h1>
          <p className="text-sm text-red-600 font-medium mb-6">
            Save your credentials now — the secret will NOT be shown again.
          </p>

          <div className="bg-gray-900 text-green-400 rounded p-4 font-mono text-sm mb-4 select-all">
            <p>FID: {oneTimeFidSecret.fid}</p>
            <p>Secret: {oneTimeFidSecret.fidSecret}</p>
          </div>

          <div className="flex gap-3">
            <button
              onClick={handleCopy}
              className="flex-1 bg-gray-200 hover:bg-gray-300 text-gray-700 font-medium py-2 px-4 rounded transition-colors"
            >
              {copied ? 'Copied!' : 'Copy to Clipboard'}
            </button>
            <button
              onClick={() => navigate('/login')}
              className="flex-1 bg-indigo-600 hover:bg-indigo-700 text-white font-medium py-2 px-4 rounded transition-colors"
            >
              Proceed to Login
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-lg mx-auto mt-10">
      <div className="bg-white shadow-md rounded-lg p-8">
        <h1 className="text-2xl font-bold text-gray-800 mb-6">
          Register Tenant
        </h1>

        {error && (
          <div
            role="alert"
            className="mb-4 p-3 bg-red-50 border border-red-200 rounded text-red-700 text-sm"
          >
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} noValidate>
          {(
            [
              { id: 'name', label: 'Organisation Name', type: 'text', required: true },
              { id: 'slug', label: 'Slug (URL-friendly identifier)', type: 'text', required: true },
              { id: 'contactEmail', label: 'Contact Email', type: 'email', required: true },
              { id: 'maxPipelines', label: 'Max Pipelines', type: 'number', required: true },
              { id: 'maxTotalParallelism', label: 'Max Total Parallelism', type: 'number', required: true },
            ] as const
          ).map(({ id, label, type }) => (
            <div key={id} className="mb-4">
              <label
                htmlFor={id}
                className="block text-sm font-medium text-gray-700 mb-1"
              >
                {label}
              </label>
              <input
                id={id}
                name={id}
                type={type}
                value={form[id as keyof RegistrationForm]}
                onChange={handleChange}
                className={`w-full border rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 ${
                  fieldErrors[id]
                    ? 'border-red-400'
                    : 'border-gray-300'
                }`}
              />
              {fieldErrors[id] && (
                <p className="mt-1 text-xs text-red-600">{fieldErrors[id]}</p>
              )}
            </div>
          ))}

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-400 text-white font-medium py-2 px-4 rounded transition-colors mt-2"
          >
            {loading ? 'Registering…' : 'Register'}
          </button>
        </form>

        <p className="mt-4 text-center text-sm text-gray-500">
          Already registered?{' '}
          <a href="/login" className="text-indigo-600 hover:underline">
            Sign in
          </a>
        </p>
      </div>
    </div>
  );
}
